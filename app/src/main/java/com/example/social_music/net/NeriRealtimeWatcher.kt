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
    private val onRoomClosed: () -> Unit,
    /**
     * join 成功、确认「房间真实存在且密钥有效」时回调。
     * 上层靠它决定要不要去后端开房 —— 先验证再开房，避免留下幽灵房。
     */
    private val onConnected: () -> Unit
) {
    companion object {
        private const val TAG = "NeriWatcher"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PREF_NAME = "neri_watcher_prefs"
        private const val KEY_DEVICE_UUID = "device_uuid"

        /** 断线后静默重连的延迟 */
        private const val RETRY_DELAY_MS = 3_000L

        /** 断线时那次「房间还在不在」探测的超时，必须短，不能把重连拖住 */
        private const val PROBE_TIMEOUT_MS = 3_000L
    }

    /**
     * 专用于存活探测的客户端：超时开得很短。
     * 复用主客户端不行 —— 它的读超时是 12 秒，探测一旦卡住会把重连一起拖死。
     */
    private val probeClient = OkHttpClient.Builder()
        .dns(EdgeDns)
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // ⚡ 每台设备持久化分配独立 UUID，杜绝多个用户共用相同静态 UUID 导致互相顶号
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
        .dns(EdgeDns)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // 下面这些字段会被 OkHttp 的回调线程读写，统一标 @Volatile
    @Volatile private var activeWebSocket: WebSocket? = null
    @Volatile private var activeJoinCall: Call? = null
    @Volatile private var currentRoomId: String? = null
    @Volatile private var isClosedManually = false
    @Volatile private var isConnecting = false

    /**
     * 每次 startWatching 自增，回调里靠它判断自己是不是「上一代」连接。
     *
     * 不能拿 socket 引用做比对：newWebSocket() 返回之前回调就可能已经触发，
     * 那一刻 activeWebSocket 还是 null，于是 onClosing / onClosed / onFailure
     * 会被整个丢掉 —— 长连接早已断开却永远不重连，房间没了也永远不知道。
     * 表现就是房主端一直停在「你正在放歌」、谁也关不掉。
     */
    @Volatile private var generation = 0

    /**
     * 预热到播放器服务器的 TCP+TLS。
     *
     * 开房那一次 join 是**新建连接**，握手成本实测最坏到过 5.7 秒，全砸在用户点「开启」
     * 之后。而连接池是按 OkHttpClient 实例算的，所以必须用**这个类自己的 client**
     * 去预热才有意义 —— 拿别的 client 预热，连接落在别的池子里，等于白做。
     *
     * 用户粘完口令、手指还没点到「开启」的那一两秒就是白捡的时间。
     * 这个方法本身不产生任何业务副作用：只发一个读得到响应体的轻量 GET，
     * 让 TCP+TLS 建好并把连接还回池子，**不会**去 join 房间（那会往房间里塞一个机器人）。
     */
    fun preconnect(serverUrl: String) {
        val base = serverUrl.trim().removeSuffix("/")
        if (base.isEmpty()) return

        Thread {
            try {
                val request = Request.Builder().url("$base/api/health").get().build()
                client.newCall(request).execute().use { response ->
                    // 必须把响应体读完，OkHttp 才会把这个连接放回池子复用
                    response.body?.string()
                    Log.i(TAG, "播放器连接预热完成 code=${response.code}")
                }
            } catch (e: Exception) {
                // 预热失败无所谓，真正 join 的时候会照常再连一次
                Log.i(TAG, "播放器连接预热失败（忽略）: ${e.message}")
            }
        }.start()
    }

    @Synchronized
    fun startWatching(serverUrl: String, roomId: String, secret: String, userNickname: String = "群友伴侣") {
        if (currentRoomId == roomId && (activeWebSocket != null || isConnecting)) {
            return
        }

        stopWatching()
        // stopWatching() 已经把上一代作废，这里取一个新的代号
        val myGeneration = ++generation
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

                if (myGeneration != generation) return@Thread

                // 404 或 410 说明房间在服务端已销毁
                if (joinResp.code in listOf(404, 410)) {
                    Log.w(TAG, "房间在服务端已失效: code=${joinResp.code}")
                    notifyRoomClosed(myGeneration)
                    return@Thread
                }

                if (!joinResp.isSuccessful || respBody.isEmpty()) {
                    scheduleSilentRetry(serverUrl, roomId, secret, userNickname, myGeneration)
                    return@Thread
                }

                val json = JSONObject(respBody)
                if (!json.optBoolean("ok", false)) {
                    val err = json.optString("error", "")
                    if (err.contains("not found", ignoreCase = true) || err.contains("missing", ignoreCase = true)) {
                        notifyRoomClosed(myGeneration)
                    } else {
                        scheduleSilentRetry(serverUrl, roomId, secret, userNickname, myGeneration)
                    }
                    return@Thread
                }

                val token = json.optString("token", "")
                val initState = json.optJSONObject("state")
                initState?.let { parseAndDispatchState(it) }

                // join 已经被服务端接受 —— 口令是真的，房间也真的存在
                notifyConnected(myGeneration)

                if (token.isEmpty() || isClosedManually || myGeneration != generation) return@Thread

                val wsUrl = baseUrl.replaceFirst("http://", "ws://")
                    .replaceFirst("https://", "wss://") + "/api/rooms/$roomId/ws?token=$token"

                val wsRequest = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val newWs = client.newWebSocket(wsRequest, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (myGeneration != generation) return
                        isConnecting = false
                        Log.i(TAG, "🚀 Neri WebSocket 连接成功！正在监听切歌事件...")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (myGeneration != generation) return
                        try {
                            val msg = JSONObject(text)
                            val type = msg.optString("type", "")
                            // ⚡ 仅当收到明确的解散通知时，才回调房间关闭
                            if (type == "ROOM_CLOSED" || type == "ROOM_DESTROYED") {
                                notifyRoomClosed(myGeneration)
                                return
                            }
                            val state = msg.optJSONObject("state") ?: msg
                            parseAndDispatchState(state)
                        } catch (e: Exception) {
                            Log.e(TAG, "解析广播异常", e)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (myGeneration != generation) return
                        // 对端发起关闭，回一个关闭帧收尾。
                        // 真正的恢复动作统一放在 onClosed 里 ——
                        // 两边都做会把探测和重连各跑一遍。
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (myGeneration != generation || isClosedManually) return
                        forgetSocket(webSocket, myGeneration)
                        Log.w(TAG, "长连接已关闭(code=$code)，先确认房间是否还在")
                        probeRoomThenRecover(serverUrl, roomId, secret, userNickname, myGeneration)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (myGeneration != generation || isClosedManually) return
                        forgetSocket(webSocket, myGeneration)
                        Log.w(TAG, "WebSocket 波动异常: ${t.message}")
                        // 404/410 = 房间已经没了，重连多少次都没用
                        if (response?.code in listOf(404, 410)) {
                            notifyRoomClosed(myGeneration)
                        } else {
                            probeRoomThenRecover(serverUrl, roomId, secret, userNickname, myGeneration)
                        }
                    }
                })

                activeWebSocket = newWs

            } catch (e: Exception) {
                if (!isClosedManually) {
                    scheduleSilentRetry(serverUrl, roomId, secret, userNickname, myGeneration)
                }
            } finally {
                // 只有仍是「当代」连接时才回收这些标记，
                // 否则会把新一代连接的状态一并清掉
                if (myGeneration == generation) {
                    activeJoinCall = null
                    // 无论走哪条分支都要清掉「正在连接」标记。
                    // 那些提前 return 的分支（房间失效 / token 为空）如果漏掉它，
                    // startWatching 的幂等检查会认为连接还在，同一房间号就再也连不上了。
                    isConnecting = false
                }
            }
        }.start()
    }

    /**
     * 连接已经死了：把引用清掉。
     *
     * 这一步绝不能省。[startWatching] 的幂等判断是
     * 「同一个房间号 && activeWebSocket != null → 直接返回」，
     * 而 OkHttp 在 onClosed / onFailure 之后**不会**帮我们把这个引用置空。
     *
     * 结果就是：长连接一掉线，重连请求会被自己的幂等判断挡回去 ——
     * 连接永远不再建立，切歌事件从此再也收不到，而且**再也不会自己好**。
     * 以前 Activity 销毁时会 stopWatching() 顺手清掉它，等于把一个 bug 兜住了；
     * 现在会话要活过 Activity，这个兜底就没有了，必须在这里显式清。
     */
    @Synchronized
    private fun forgetSocket(dead: WebSocket, myGeneration: Int) {
        if (myGeneration != generation) return
        // 只清自己那一条，别把新一代的连接误伤掉
        if (activeWebSocket === dead) activeWebSocket = null
    }

    /**
     * 长连接断开时的处理：先花几百毫秒问一次「房间还在不在」，再决定重连还是上报关闭。
     *
     * 以前是直接排一个 3 秒后的重连，靠重连时的 join 拿 404 才知道房间没了 ——
     * 光这一步就固定吃掉 3 秒多。改成先探测，房间没了就能立刻上报，
     * 只有确实还在（普通网络抖动）才走 3 秒重连。
     *
     * 用的是只读的 GET /api/rooms/{id}/state，不会像 join 那样往房间里塞机器人。
     */
    private fun probeRoomThenRecover(
        serverUrl: String,
        roomId: String,
        secret: String,
        userNickname: String,
        myGeneration: Int
    ) {
        Thread {
            if (myGeneration != generation || isClosedManually) return@Thread

            val gone = try {
                val req = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/rooms/$roomId/state")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { it.code == 404 || it.code == 410 }
            } catch (e: Exception) {
                // 探测本身失败（比如真的没网）不能当成「房间没了」
                false
            }

            if (myGeneration != generation || isClosedManually) return@Thread

            if (gone) {
                Log.w(TAG, "探测到房间已不存在，立即上报关闭")
                notifyRoomClosed(myGeneration)
            } else {
                scheduleSilentRetry(serverUrl, roomId, secret, userNickname, myGeneration)
            }
        }.start()
    }

    private fun scheduleSilentRetry(
        serverUrl: String,
        roomId: String,
        secret: String,
        userNickname: String,
        myGeneration: Int
    ) {
        // 上一代连接的重试不能把新一代连接搅乱
        if (myGeneration != generation) return
        isConnecting = false
        if (isClosedManually) return

        mainHandler.postDelayed({
            if (myGeneration == generation && !isClosedManually && currentRoomId == roomId) {
                startWatching(serverUrl, roomId, secret, userNickname)
            }
        }, RETRY_DELAY_MS)
    }

    private fun notifyConnected(myGeneration: Int) {
        if (myGeneration != generation || isClosedManually) return
        mainHandler.post {
            if (myGeneration == generation) onConnected()
        }
    }

    private fun notifyRoomClosed(myGeneration: Int) {
        if (myGeneration != generation || isClosedManually) return
        mainHandler.post {
            // 到主线程时可能已经换了房间，必须再确认一次
            if (myGeneration == generation) onRoomClosed()
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
        // 让所有在途的回调与重试立即失效
        generation++
        isClosedManually = true
        isConnecting = false
        // ⚡ 彻底清除 Handler 内部所有待执行的重试任务，防止新房间上线时被旧重试回调打乱
        mainHandler.removeCallbacksAndMessages(null)
        activeJoinCall?.cancel()
        activeJoinCall = null
        activeWebSocket?.close(1000, "Normal Close")
        activeWebSocket = null
        currentRoomId = null
    }
}