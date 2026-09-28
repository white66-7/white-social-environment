package com.example.social_music.net

import com.example.social_music.model.ActiveRoom
import com.example.social_music.model.MemberInfo
import com.example.social_music.model.PlaybackPayload
import com.example.social_music.model.RoomInfo
import com.example.social_music.model.RoomSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class StateOutcome {
    data class Ok(val snapshot: RoomSnapshot) : StateOutcome()
    object Unauthorized : StateOutcome()
    data class Failed(val message: String?) : StateOutcome()
}

sealed class StartOutcome {
    /**
     * [pending] = 服务端先受理了一个「未确认」房间，它此刻对成员端还不可见 ——
     * 口令校验通过后要靠 confirm 上报才会亮出来。见 MainActivity 的并行开房流程。
     */
    data class Ok(val room: ActiveRoom?, val pending: Boolean = false) : StartOutcome()
    data class Conflict(val message: String) : StartOutcome()
    object Unauthorized : StartOutcome()
    data class Failed(val message: String?) : StartOutcome()
}

sealed class PushOutcome {
    object Ok : PushOutcome()

    /** 服务端已经把这条判为迟到/重复，丢弃了。不是错误。 */
    object Stale : PushOutcome()

    /** 服务端已经没有属于我的房间了：心跳超时被回收，或者已被别人接管 */
    object RoomLost : PushOutcome()
    object Unauthorized : PushOutcome()
    data class Failed(val message: String?) : PushOutcome()
}

sealed class ProfileOutcome {
    data class Ok(val qq: String, val username: String, val avatarUrl: String) : ProfileOutcome()
    object Unauthorized : ProfileOutcome()
    data class Failed(val message: String?) : ProfileOutcome()
}

class RoomApiService {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun parseSnapshot(json: JSONObject): RoomSnapshot = RoomSnapshot(
            version = json.optLong("version", 0L),
            serverTime = json.optLong("serverTime", System.currentTimeMillis()),
            room = parseRoom(json.optJSONObject("room")),
            members = parseMembers(json.optJSONArray("members"))
        )

        /**
         * org.json 的经典陷阱：JSON 里的 null 是 JSONObject.NULL 这个哨兵对象，
         * optString 会把它 toString 成字面量 "null" —— 于是「当前没有歌曲」
         * 就变成了一首名叫 null 的歌，封面还会去请求 https://.../null。
         * 所有可空字符串字段都必须走这里读。
         */
        private fun JSONObject.optNullableString(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").trim().ifEmpty { null }
        }

        fun parseRoom(obj: JSONObject?): ActiveRoom? {
            if (obj == null) return null
            val roomId = obj.optNullableString("roomId") ?: return null

            return ActiveRoom(
                roomId = roomId,
                inviter = obj.optNullableString("inviter"),
                publisher = obj.optNullableString("publisher"),
                publisherQq = obj.optNullableString("publisherQq"),
                hostAvatarUrl = obj.optNullableString("hostAvatarUrl"),
                deepLink = obj.optNullableString("deepLink"),
                serverUrl = obj.optNullableString("serverUrl"),
                currentSong = obj.optNullableString("currentSong"),
                currentCover = obj.optNullableString("currentCover"),
                durationMs = obj.optLong("durationMs", 0L),
                basePositionMs = obj.optLong("basePositionMs", 0L),
                baseTimestampMs = obj.optLong("baseTimestampMs", 0L),
                playbackRate = obj.optDouble("playbackRate", 1.0),
                isPlaying = obj.optBoolean("isPlaying", false)
            )
        }

        fun parseMembers(arr: JSONArray?, defaultOnline: Boolean = true): List<MemberInfo> {
            if (arr == null) return emptyList()
            val out = ArrayList<MemberInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optNullableString("username") ?: continue
                out.add(
                    MemberInfo(
                        qq = obj.optNullableString("qq").orEmpty(),
                        username = name,
                        avatarUrl = obj.optNullableString("avatarUrl").orEmpty(),
                        isHosting = obj.optBoolean("isHosting", false),
                        isOnline = if (obj.has("isOnline")) {
                            obj.optBoolean("isOnline", defaultOnline)
                        } else {
                            defaultOnline
                        }
                    )
                )
            }
            return out
        }

        private fun errorMessage(body: String, code: Int): String {
            return try {
                JSONObject(body).optString("message").ifBlank { "请求失败($code)" }
            } catch (_: Exception) {
                "请求失败($code)"
            }
        }
    }

    // ==========================================================
    // 房间状态
    // ==========================================================

    /** 一次性快照。长连接握手前先用它把首屏画出来，同时顺带验证 token。 */
    suspend fun fetchState(token: String): StateOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/room/state")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> StateOutcome.Unauthorized
                    response.isSuccessful && body.isNotEmpty() ->
                        StateOutcome.Ok(parseSnapshot(JSONObject(body)))
                    else -> StateOutcome.Failed(errorMessage(body, response.code))
                }
            }
        } catch (e: Exception) {
            StateOutcome.Failed(e.message)
        }
    }

    suspend fun fetchProfile(token: String): ProfileOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/user/me")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) return@use ProfileOutcome.Unauthorized
                if (!response.isSuccessful || body.isEmpty()) {
                    return@use ProfileOutcome.Failed(errorMessage(body, response.code))
                }
                val data = JSONObject(body).optJSONObject("data") ?: JSONObject()
                ProfileOutcome.Ok(
                    qq = data.optString("qq"),
                    username = data.optString("username"),
                    avatarUrl = data.optString("avatarUrl")
                )
            }
        } catch (e: Exception) {
            ProfileOutcome.Failed(e.message)
        }
    }

    /**
     * 全部注册成员的花名册。名单本身是稳定的，只有 isOnline / isHosting
     * 会随长连接实时变化，所以这里拉到的在线状态只是初值。
     */
    suspend fun fetchRoster(token: String): Result<List<MemberInfo>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/user/list")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isEmpty()) {
                    return@use Result.failure(Exception(errorMessage(body, response.code)))
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("data") ?: json.optJSONArray("list")
                Result.success(parseMembers(arr, defaultOnline = false))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================================
    // 房主操作
    // ==========================================================

    /**
     * 开房。[pending] = true 时服务端先受理一个「未确认」房间：
     * 它对成员端不可见，等口令校验通过后由 [hostPushState] 的 confirm 点亮。
     * 这样开房可以和播放器的 join 并行，省掉一整次跨境往返。
     */
    suspend fun hostStart(
        token: String,
        info: RoomInfo,
        pending: Boolean = false
    ): StartOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("roomId", info.roomId)
            put("inviter", info.inviter ?: "")
            put("secret", info.secret ?: "")
            put("deepLink", info.rawUri)
            put("serverUrl", info.serverUrl ?: ApiConfig.DEFAULT_NERI_SERVER)
            if (pending) put("pending", true)
        }

        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/room/host/start")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    JSONObject()
                }

                when {
                    response.code == 401 -> StartOutcome.Unauthorized
                    response.code == 409 -> StartOutcome.Conflict(
                        json.optString("message").ifBlank { "当前已有其他人在放歌" }
                    )
                    response.isSuccessful -> StartOutcome.Ok(
                        room = parseRoom(json.optJSONObject("data")),
                        pending = json.optBoolean("pending", false)
                    )
                    else -> StartOutcome.Failed(
                        json.optString("message").ifBlank { "开启失败(${response.code})" }
                    )
                }
            }
        } catch (e: Exception) {
            StartOutcome.Failed("网络异常：${e.message}")
        }
    }

    /**
     * 上报播放状态；playback 为 null 时是纯保活心跳。
     *
     * [confirm] 用于把服务端那个「未确认」的 pending 房间点亮（口令校验通过时）。
     *
     * [onCall] 在请求真正发出前回调，让调用方拿到 [Call] 以便取消 ——
     * 切歌要能立刻顶掉一次在途的、已经过时的位置上报，不然就得排在它后面等上好几秒。
     */
    suspend fun hostPushState(
        token: String,
        playback: PlaybackPayload?,
        confirm: Boolean = false,
        neriToken: String? = null,
        onCall: ((Call) -> Unit)? = null
    ): PushOutcome =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
            // 播放器那边的成员 token，服务端存活探测要用（不带 token 一律 401）
            if (!neriToken.isNullOrEmpty()) payload.put("neriToken", neriToken)
            if (playback != null) {
                payload.put("playback", JSONObject().apply {
                    put("currentSong", playback.currentSong ?: JSONObject.NULL)
                    put("currentCover", playback.currentCover ?: JSONObject.NULL)
                    put("durationMs", playback.durationMs)
                    put("basePositionMs", playback.basePositionMs)
                    put("isPlaying", playback.isPlaying)
                    put("playbackRate", playback.playbackRate)
                    if (playback.seq > 0) put("seq", playback.seq)
                })
            }
            if (confirm) payload.put("confirm", true)

            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}/room/host/state")
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            try {
                val call = Http.client.newCall(request)
                onCall?.invoke(call)
                call.execute().use { response ->
                    when {
                        response.code == 401 -> PushOutcome.Unauthorized
                        // 404 = 房间没了，403 = 房主换人了，对本地而言都是「丢了」
                        response.code == 404 || response.code == 403 -> PushOutcome.RoomLost
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            // 服务端说这条迟到/重复，已经被丢弃 —— 不是错误，但要知道
                            if (runCatching { JSONObject(body).optBoolean("stale", false) }
                                    .getOrDefault(false)
                            ) PushOutcome.Stale else PushOutcome.Ok
                        }
                        else -> {
                            val body = response.body?.string().orEmpty()
                            PushOutcome.Failed(errorMessage(body, response.code))
                        }
                    }
                }
            } catch (e: Exception) {
                PushOutcome.Failed(e.message)
            }
        }

    /**
     * 关播。
     *
     * [roomId] 用来把这次关房**限定在指定的那个房间**上。不带的话服务端按
     * 「关掉这个用户当前的房间」处理 —— 一旦有迟到请求（关播后立刻重开、
     * 或一次已作废的开房请求延迟撤销），它会把刚开好的新房一起关掉，
     * 而界面还显示着「房间上线成功」。所以凡是知道房间号的调用都要带上。
     */
    suspend fun hostStop(token: String, roomId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            if (!roomId.isNullOrEmpty()) put("roomId", roomId)
        }

        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/room/host/stop")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            Http.client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateUsername(token: String, newName: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply { put("newUsername", newName) }
            val request = Request.Builder()
                .url("${ApiConfig.BASE_URL}/user/update-name")
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            try {
                Http.client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        true to null
                    } else {
                        false to errorMessage(body, response.code)
                    }
                }
            } catch (e: Exception) {
                false to "网络异常：${e.message}"
            }
        }
}
