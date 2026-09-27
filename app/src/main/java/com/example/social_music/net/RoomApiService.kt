package com.example.social_music.net

import com.example.social_music.model.RegisteredMember
import com.example.social_music.model.RoomInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RoomApiService {

    companion object {
        const val BASE_URL = "https://white667.xyz/api"
        const val DEFAULT_NERI_SERVER = "https://neriplayer.hancat.work"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // 状态响应封装
    data class StatusResult(
        val exists: Boolean,
        val inviter: String? = null,
        val publisher: String? = null,
        val hostAvatarUrl: String? = null,
        val deepLink: String? = null,
        val currentSong: String? = null,
        val currentCover: String? = null,
        val durationMs: Long = 0L,
        val basePositionMs: Long = 0L,
        val baseTimestampMs: Long = 0L,
        val playbackRate: Double = 1.0,
        val isPlaying: Boolean = true
    )

    data class BroadcastResult(
        val isSuccess: Boolean,
        val message: String? = null,
        val code: Int = 200,
        val statusData: StatusResult? = null
    )

    /**
     * 查询房间状态
     */
    suspend fun getRoomStatus(force: Boolean = false): Result<StatusResult> = withContext(Dispatchers.IO) {
        val url = if (force) "$BASE_URL/room/status?force=true" else "$BASE_URL/room/status"
        val request = Request.Builder().url(url).get().build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val exists = json.optBoolean("exists", false)
                val status = StatusResult(
                    exists = exists,
                    inviter = json.optString("inviter", "").ifEmpty { null },
                    publisher = json.optString("publisher", "").ifEmpty { null },
                    hostAvatarUrl = json.optString("hostAvatarUrl", "").ifEmpty { null },
                    deepLink = json.optString("deepLink", "").ifEmpty { null },
                    currentSong = json.optString("currentSong", "").ifEmpty { null },
                    currentCover = json.optString("currentCover", "").ifEmpty { null },
                    durationMs = json.optLong("durationMs", 0L),
                    basePositionMs = json.optLong("basePositionMs", 0L),
                    baseTimestampMs = json.optLong("baseTimestampMs", System.currentTimeMillis()),
                    playbackRate = json.optDouble("playbackRate", 1.0),
                    isPlaying = json.optBoolean("isPlaying", true)
                )
                Result.success(status)
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发送房间广播（开播/恢复/停止）
     */
    suspend fun broadcastRoom(
        action: String,
        username: String,
        info: RoomInfo?,
        isResume: Boolean = false
    ): BroadcastResult = withContext(Dispatchers.IO) {
        val effectiveInviter = if (!info?.inviter.isNullOrBlank()) info.inviter else username
        val json = JSONObject().apply {
            put("action", action)
            put("username", username)
            if (isResume) put("resume", true)
            if (info != null) {
                put("roomId", info.roomId)
                put("inviter", effectiveInviter)
                put("publisher", username)
                put("secret", info.secret)
                put("deepLink", info.rawUri)
                put("serverUrl", info.serverUrl ?: DEFAULT_NERI_SERVER)
            }
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$BASE_URL/broadcast").post(requestBody).build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val isOk = response.isSuccessful

            var errMsg: String? = null
            var statusData: StatusResult? = null

            if (!isOk) {
                errMsg = "操作失败(${response.code})"
                try {
                    val errJson = JSONObject(body)
                    errMsg = errJson.optString("message", errMsg)
                } catch (_: Exception) {}
            } else {
                try {
                    val resData = JSONObject(body).optJSONObject("data")
                    if (resData != null) {
                        statusData = StatusResult(
                            exists = true,
                            inviter = effectiveInviter,
                            publisher = username,
                            deepLink = info?.rawUri,
                            hostAvatarUrl = resData.optString("hostAvatarUrl", "").ifEmpty { null },
                            currentSong = resData.optString("currentSong", "").ifEmpty { null },
                            currentCover = resData.optString("currentCover", "").ifEmpty { null },
                            durationMs = resData.optLong("durationMs", 0L),
                            basePositionMs = resData.optLong("basePositionMs", 0L),
                            baseTimestampMs = resData.optLong("baseTimestampMs", System.currentTimeMillis()),
                            playbackRate = resData.optDouble("playbackRate", 1.0),
                            isPlaying = resData.optBoolean("isPlaying", true)
                        )
                    }
                } catch (_: Exception) {}
            }
            BroadcastResult(isSuccess = isOk, message = errMsg, code = response.code, statusData = statusData)
        } catch (e: Exception) {
            BroadcastResult(isSuccess = false, message = "网络异常：${e.message}", code = -1)
        }
    }

    /**
     * 发送心跳包（⚡ 关键升级：支持将房主当前播放的实时歌曲同步到服务器 Redis，供所有群友获取）
     */
    suspend fun sendHeartbeat(
        username: String,
        currentSong: String? = null,
        currentCover: String? = null,
        durationMs: Long = 0L,
        basePositionMs: Long = 0L,
        isPlaying: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("action", "heartbeat")
            put("username", username)
            // 房主将 WebSocket 拿到的实时歌曲同步至服务端 Redis
            if (!currentSong.isNullOrEmpty()) {
                put("currentSong", currentSong)
                put("currentCover", currentCover ?: "")
                put("durationMs", durationMs)
                put("basePositionMs", basePositionMs)
                put("isPlaying", isPlaying)
            }
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$BASE_URL/broadcast").post(requestBody).build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 获取成员列表
     */
    suspend fun fetchMemberList(token: String?): Result<List<RegisteredMember>> = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$BASE_URL/user/list").get()
        if (!token.isNullOrEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()
            val list = mutableListOf<RegisteredMember>()

            if (response.isSuccessful && body.isNotEmpty()) {
                val trimmed = body.trim()
                val dataArray = if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    val json = JSONObject(trimmed)
                    json.optJSONArray("data")
                        ?: json.optJSONArray("users")
                        ?: json.optJSONArray("list")
                        ?: json.optJSONArray("members")
                }

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        val name = obj.optString("username", obj.optString("name", "")).trim()
                        if (name.isNotEmpty()) {
                            list.add(
                                RegisteredMember(
                                    username = name,
                                    avatarUrl = obj.optString("avatarUrl", obj.optString("avatar", "")),
                                    isHosting = obj.optBoolean("isHosting", false)
                                )
                            )
                        }
                    }
                }
                Result.success(list)
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}