package com.example.social_music.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class NeriRealtimeWatcher(
    private val context: Context,
    private val onPlaybackUpdate: (
        songTitle: String?,
        artist: String?,
        coverUrl: String?,
        durationMs: Long,
        basePosMs: Long,
        baseTimestampMs: Long,
        playbackRate: Double,
        isPlaying: Boolean
    ) -> Unit,
    private val onRoomClosed: () -> Unit
) {
    companion object {
        private const val TAG = "NeriWatcher"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PREF_NAME = "neri_watcher_prefs"
        private const val KEY_DEVICE_UUID = "device_uuid"
    }

    // ⚡ 核心修复：为每台设备分配持久化唯一 UUID，杜绝多个用户互相把对方踢下线
    private val deviceUuid: String by lazy {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var id = sp.getString(KEY_DEVICE_UUID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_UUID, id).apply()
        }
        id
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeWebSocket: WebSocket? = null
    private var activeJoinCall: Call? = null
    private var currentRoomId: String? = null
    private var isClosedManually = false
    private var isConnecting = false

    @Synchronized
    fun startWatching(serverUrl: String, roomId: String, secret: String, userNickname: String = "群友伴侣") {
        if (currentRoomId == roomId && (activeWebSocket != null || isConnecting)) {
            return
        }

        stopWatching()
        currentRoomId = roomId
        isClosedManually = false
        isConnecting = true

        Thread {
            try {
                val baseUrl = serverUrl.trimEnd('/')
                val joinUrl = "$baseUrl/api/rooms/$roomId/join"

                val joinPayload = JSONObject().apply {
                    put("userUuid", deviceUuid)
                    put("nickname", userNickname.ifBlank { "群友伴侣" })
                    put("joinSecret", secret)
                }

                val joinReq = Request.Builder()
                    .url(joinUrl)
                    .post(joinPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                activeJoinCall = client.newCall(joinReq)
                val joinResp = activeJoinCall?.execute() ?: return@Thread
                val respBody = joinResp.body?.string().orEmpty()

                // 404 或 410 说明房间在服务端已销毁
                if (joinResp.code in listOf(404, 410)) {
                    Log.w(TAG, "房间在服务端已失效: code=${joinResp.code}")
                    notifyRoomClosed()
                    return@Thread
                }

                if (!joinResp.isSuccessful || respBody.isEmpty()) {
                    scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                    return@Thread
                }

                val json = JSONObject(respBody)
                if (!json.optBoolean("ok", false)) {
                    val err = json.optString("error", "")
                    if (err.contains("not found", ignoreCase = true) || err.contains("missing", ignoreCase = true)) {
                        notifyRoomClosed()
                    } else {
                        scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                    }
                    return@Thread
                }

                val token = json.optString("token", "")
                val initState = json.optJSONObject("state")
                initState?.let { parseAndDispatchState(it) }

                if (token.isEmpty() || isClosedManually) return@Thread

                val wsUrl = baseUrl.replaceFirst("http://", "ws://")
                    .replaceFirst("https://", "wss://") + "/api/rooms/$roomId/ws?token=$token"

                val wsRequest = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val newWs = client.newWebSocket(wsRequest, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (webSocket !== activeWebSocket) return
                        isConnecting = false
                        Log.i(TAG, "🚀 Neri WebSocket 连接成功！监听中...")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (webSocket !== activeWebSocket) return
                        try {
                            val msg = JSONObject(text)
                            val type = msg.optString("type", "")
                            // ⚡ 仅当收到明确的解散通知时，才回调房间关闭
                            if (type == "ROOM_CLOSED" || type == "ROOM_DESTROYED") {
                                notifyRoomClosed()
                                return
                            }
                            val state = msg.optJSONObject("state") ?: msg
                            parseAndDispatchState(state)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析广播异常", e)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // ⚡ 网络原因正常关闭，严禁当作房间解散，做静默重连
                        if (webSocket !== activeWebSocket || isClosedManually) return
                        scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (webSocket !== activeWebSocket || isClosedManually) return
                        scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (webSocket !== activeWebSocket || isClosedManually) return
                        Log.w(TAG, "WebSocket 连接波动: ${t.message}，正在静默重连...")
                        if (response?.code in listOf(404, 410)) {
                            notifyRoomClosed()
                        } else {
                            scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                        }
                    }
                })

                activeWebSocket = newWs

            } catch (e: Exception) {
                if (!isClosedManually) {
                    scheduleSilentRetry(serverUrl, roomId, secret, userNickname)
                }
            } finally {
                activeJoinCall = null
            }
        }.start()
    }

    private fun scheduleSilentRetry(serverUrl: String, roomId: String, secret: String, userNickname: String) {
        isConnecting = false
        if (isClosedManually) return
        mainHandler.postDelayed({
            if (!isClosedManually && currentRoomId == roomId) {
                startWatching(serverUrl, roomId, secret, userNickname)
            }
        }, 3000L)
    }

    private fun notifyRoomClosed() {
        if (isClosedManually) return
        mainHandler.post {
            onRoomClosed()
        }
    }

    /**
     * ⚡ 深度兼容不同音源的字段格式（name / title / picUrl / cover）
     */
    private fun parseAndDispatchState(state: JSONObject) {
        val track = state.optJSONObject("track")
            ?: run {
                val queue = state.optJSONArray("queue")
                val curIdx = state.optInt("currentIndex", -1)
                if (queue != null && curIdx in 0 until queue.length()) {
                    queue.optJSONObject(curIdx)
                } else null
            }

        val songTitle = track?.let { t ->
            val n = t.optString("name", "").trim()
            if (n.isNotEmpty() && n != "null") n
            else {
                val title = t.optString("title", "").trim()
                if (title.isNotEmpty() && title != "null") title else null
            }
        }

        val artist = track?.let { t ->
            val a = t.optString("artist", "").trim()
            if (a.isNotEmpty() && a != "null") a
            else {
                val author = t.optString("author", "").trim()
                if (author.isNotEmpty() && author != "null") author else "未知歌手"
            }
        } ?: "未知歌手"

        val currentCover = track?.let { t ->
            val c = t.optString("coverUrl", "").trim()
            if (c.isNotEmpty() && c != "null") c
            else {
                val pic = t.optString("picUrl", "").trim()
                if (pic.isNotEmpty() && pic != "null") pic
                else {
                    val cv = t.optString("cover", "").trim()
                    if (cv.isNotEmpty() && cv != "null") cv else null
                }
            }
        }

        val durationMs = track?.optLong("durationMs", 0L) ?: 0L

        val playback = state.optJSONObject("playback")
        val isPlaying = playback?.optString("state", "playing") == "playing"
        val basePosMs = playback?.optLong("basePositionMs", 0L) ?: 0L
        val baseTimestampMs = playback?.optLong("baseTimestampMs", System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val rate = playback?.optDouble("playbackRate", 1.0) ?: 1.0

        mainHandler.post {
            onPlaybackUpdate(
                songTitle,
                artist,
                currentCover,
                durationMs,
                basePosMs,
                baseTimestampMs,
                rate,
                isPlaying
            )
        }
    }

    @Synchronized
    fun stopWatching() {
        isClosedManually = true
        isConnecting = false
        activeJoinCall?.cancel()
        activeJoinCall = null
        activeWebSocket?.close(1000, "Normal Close")
        activeWebSocket = null
        currentRoomId = null
    }
}