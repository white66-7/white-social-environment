package com.example.social_music.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.social_music.model.RoomSnapshot
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 成员端唯一的一条实时长连接，直连后端 RoomHub。
 *
 * 这里取代了旧架构中「每个客户端各自去 join 一次 NeriPlayer 房间」的做法 ——
 * 那会让房间里凭空多出一堆名为「群友伴侣」的机器人。现在只有房主连 Neri，
 * 其余人只连这里，由服务器把房主上报的状态推下来。
 *
 * 服务端每次推送的是**全量快照**，所以不存在「漏一条消息就永久错位」的问题，
 * 重连之后状态自然收敛。
 */
class RoomRealtimeClient(private val listener: Listener) {

    interface Listener {
        fun onSnapshot(incoming: RoomSnapshot)

        /** version 必须带上：它是排序依据，丢掉会让迟到的 REST 快照把已关的房间又画回来 */
        fun onRoomClosed(reason: String, version: Long)
        fun onConnected()
        fun onDisconnected(reason: String?)
    }

    companion object {
        private const val TAG = "RoomRealtime"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 15_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 长连接不能设读超时，否则空闲一会儿就被判死
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // connect/disconnect 在主线程加锁调用，但 onOpen/onMessage/onClosed/onFailure
    // 跑在 OkHttp 的线程上。全部标 @Volatile，否则这些字段的读写之间没有
    // happens-before 关系，上一代的回调可能穿过 generation 检查去调度重连。
    @Volatile private var socket: WebSocket? = null
    @Volatile private var currentToken: String? = null
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var manuallyClosed = true
    @Volatile private var reconnectScheduled = false
    @Volatile private var disconnectNotified = false

    /**
     * 每次建连自增。回调里靠它判断自己是不是「上一代」连接 ——
     * 直接用 socket 引用比较会有竞态：newWebSocket 返回前回调就可能已经触发。
     */
    @Volatile private var generation = 0

    @Synchronized
    fun connect(token: String) {
        if (token.isEmpty()) return
        if (currentToken == token && !manuallyClosed && (socket != null || reconnectScheduled)) return

        teardown()
        currentToken = token
        manuallyClosed = false
        backoffMs = INITIAL_BACKOFF_MS
        disconnectNotified = false
        openSocket(token)
    }

    @Synchronized
    fun disconnect() {
        teardown()
        currentToken = null
    }

    private fun teardown() {
        manuallyClosed = true
        reconnectScheduled = false
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
    }

    private fun openSocket(token: String) {
        val myGeneration = ++generation

        val request = Request.Builder()
            .url(ApiConfig.realtimeUrl(token))
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) return
                backoffMs = INITIAL_BACKOFF_MS
                reconnectScheduled = false
                disconnectNotified = false
                Log.i(TAG, "实时通道已连接")
                mainHandler.post { listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGeneration != generation) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                scheduleReconnect("连接已关闭")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != generation) return

                // 401 是 token 失效，重连一万次也没用，直接把控制权交回上层。
                // 必须把状态收敛成「已关闭」，否则会留下一个死 socket，
                // 让后续 connect(同一 token) 撞上幂等检查变成静默空操作。
                if (response?.code == 401) {
                    Log.w(TAG, "实时通道鉴权失败，停止重连")
                    manuallyClosed = true
                    socket = null
                    mainHandler.post {
                        if (!disconnectNotified) {
                            disconnectNotified = true
                            listener.onDisconnected("登录状态已失效")
                        }
                    }
                    return
                }

                scheduleReconnect(t.message)
            }
        })

        socket = ws
    }

    private fun scheduleReconnect(cause: String?) {
        if (manuallyClosed || reconnectScheduled) return
        val token = currentToken ?: return

        reconnectScheduled = true
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)

        // 断线提示每次掉线只报一次，不然退避重连期间会一直弹提示
        if (!disconnectNotified) {
            disconnectNotified = true
            mainHandler.post { listener.onDisconnected(cause) }
        }

        mainHandler.postDelayed({
            reconnectScheduled = false
            if (!manuallyClosed && currentToken == token) {
                Log.i(TAG, "正在重连实时通道（退避 ${delay}ms）")
                openSocket(token)
            }
        }, delay)
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "无法解析的下行消息", e)
            return
        }

        when (json.optString("type")) {
            "sync" -> {
                val snapshot = try {
                    RoomApiService.parseSnapshot(json)
                } catch (e: Exception) {
                    Log.w(TAG, "快照解析失败", e)
                    return
                }
                mainHandler.post { listener.onSnapshot(snapshot) }
            }

            "room_closed" -> {
                val reason = json.optString("reason", "manual")
                val version = json.optLong("version", 0L)
                mainHandler.post { listener.onRoomClosed(reason, version) }
            }

            "pong" -> Unit
        }
    }
}
