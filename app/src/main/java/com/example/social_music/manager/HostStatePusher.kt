package com.example.social_music.manager

import android.util.Log
import com.example.social_music.model.PlaybackPayload
import com.example.social_music.net.PushOutcome
import com.example.social_music.net.RoomApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * 房主状态推送器：**同一时刻只允许一个 `/room/host/state` 请求在途**。
 *
 * 为什么必须这样。以前的写法是每收到一次 Neri 事件就 `lifecycleScope.launch` 发一个 POST，
 * 并发数无上限。而实测直连后端的往返是 1~8 秒、长尾 8~20 秒，于是：
 *
 *  1. 请求乱序落地，一次迟到的「上一首」会把刚切的新歌覆盖回去
 *     （服务端的序号保护能挡住，但客户端本来就不该乱发）；
 *  2. 一个卡死的请求占住连接，后面的新状态全都在排队 —— 用户看到的是「切歌没反应」。
 *
 * 这里用一条容量 1 的一致性通道做合并：新状态直接覆盖掉还没发出去的旧状态。
 * 位置类更新本来就可以丢 —— 成员端是靠锚点外推的，不需要高频位置。
 *
 * [push] 的 `urgent` 用于**切歌**：它会直接取消掉在途的那次上报，
 * 保证切歌永远不用排在一次 8 秒往返后面。
 *
 * 线程约定：公开方法都在主线程调用；[onOutcome] 也回到主线程派发。
 */
class HostStatePusher(
    private val scope: CoroutineScope,
    private val tokenProvider: () -> String?,
    private val onOutcome: (PushOutcome) -> Unit,
    private val api: RoomApiService = RoomApiService()
) {

    companion object {
        private const val TAG = "HostPusher"
    }

    private data class Request(val playback: PlaybackPayload?, val confirm: Boolean)

    /** 容量 1 + CONFLATED：新请求直接覆盖还没被取走的旧请求 */
    private val queue = Channel<Request>(Channel.CONFLATED)

    private var worker: Job? = null

    /**
     * 自增的上报序号，从 0 开始。
     *
     * 每次开房都会新建一个 HostStatePusher，序号自然从 0 重新开始 ——
     * 这必须和服务端在 hostStart 时把高水位归零的动作对齐，
     * 否则新会话的前几条上报会被当成「迟到」丢弃。
     */
    private var seq = 0L

    /**
     * 「还没被服务端确认」的标志。
     *
     * 必须粘住，不能只挂在那一次请求上：合并通道随时可能用一条新的播放状态
     * 覆盖掉还没发出去的 confirm 请求。真丢了的话，那个 pending 房间就永远
     * 不会对成员可见 —— 表现是「房主这边一切正常，成员端却什么都看不到」。
     * 只有收到服务端明确的 Ok 才清掉。
     */
    private var confirmOutstanding = false

    /**
     * 在途请求。标 @Volatile 是因为它在 IO 线程被赋值，却要被主线程的 `urgent` 取消。
     */
    @Volatile
    private var inFlight: Call? = null

    fun stop() {
        inFlight?.cancel()
        inFlight = null
        worker?.cancel()
        worker = null
        // CONFLATED 通道最多只缓存一条，取一次就干净了
        queue.tryReceive()
    }

    /**
     * 推送一次状态。[playback] 为 null 表示纯保活心跳。
     *
     * [urgent] = true 时取消在途请求立刻改发这条（切歌用）；
     * 否则只是覆盖待发状态，等在途请求自然结束后再发。
     */
    fun push(playback: PlaybackPayload?, confirm: Boolean = false, urgent: Boolean = false) {
        if (confirm) confirmOutstanding = true

        val next = ++seq

        // 先抓住「当前在途的那一个」，再去入队。
        //
        // 顺序反了会自伤：worker 跑在 Main.immediate 上，worker 空闲时
        // trySend 会就地唤醒它，它可能已经在新请求的 Call 上写好了 inFlight ——
        // 于是下面这句 urgent 取消的就是**刚排进去的这条新上报**，
        // 切歌反而被自己丢掉。先取引用就没有这个窗口。
        val staleCall = inFlight

        queue.trySend(
            Request(
                playback = playback?.copy(seq = next),
                // 只要还没被确认过，每条请求都捎上它
                confirm = confirmOutstanding
            )
        )

        // 切歌：把已经过时的那次位置上报掐掉，别让新歌排在它后面等一个来回
        if (urgent) staleCall?.cancel()

        ensureWorker()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return

        worker = scope.launch {
            for (req in queue) {
                val token = tokenProvider()
                if (token.isNullOrEmpty()) {
                    // 没有 token（已登出）就不发了，省一次必然 401 的往返。
                    // 下一次 push 会重新走到这里。
                    Log.w(TAG, "没有可用 token，跳过本次上报")
                    continue
                }

                val outcome = withContext(Dispatchers.IO) {
                    api.hostPushState(
                        token = token,
                        playback = req.playback,
                        confirm = req.confirm,
                        onCall = { inFlight = it }
                    )
                }
                inFlight = null

                // 只有服务端明确受理才算确认送达。
                // Stale 代表它把这条整个丢掉了（包括捎带的 confirm），所以不能清。
                if (outcome is PushOutcome.Ok) confirmOutstanding = false

                onOutcome(outcome)
            }
        }
    }
}
