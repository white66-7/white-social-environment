package com.example.social_music.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackProgressTracker(
    private val scope: CoroutineScope,
    private val onProgressTick: (progressRatio: Float) -> Unit,
    private val onSongFinished: () -> Unit
) {
    private var basePositionMs: Long = 0L
    private var baseTimestampMs: Long = 0L
    private var durationMs: Long = 0L
    private var playbackRate: Double = 1.0
    private var isPlaying: Boolean = false
    private var tickerJob: Job? = null

    /**
     * 服务器时钟与本机时钟的差值（服务器 - 本机）。
     *
     * 原先直接用本机 System.currentTimeMillis() 去减服务器下发的 baseTimestampMs，
     * 只要两端时钟差几秒，进度条就会整体偏移甚至倒着走。现在统一换算到服务器时基。
     */
    private var clockOffsetMs: Long = 0L

    fun updateServerTime(serverTimeMs: Long) {
        if (serverTimeMs <= 0) return
        clockOffsetMs = serverTimeMs - System.currentTimeMillis()
    }

    /**
     * 房主端用：它的锚点时间戳来自本机 NeriPlayer 回调，本就是本机时基，
     * 不能再叠加服务器偏移。
     */
    fun useLocalClock() {
        clockOffsetMs = 0L
    }

    fun updateMetrics(
        durationMs: Long,
        basePositionMs: Long,
        baseTimestampMs: Long,
        playbackRate: Double,
        isPlaying: Boolean
    ) {
        this.durationMs = durationMs
        this.basePositionMs = basePositionMs
        this.baseTimestampMs = baseTimestampMs
        this.playbackRate = playbackRate
        this.isPlaying = isPlaying

        tickOnce()
        start()
    }

    fun start() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                tickOnce()
                delay(500)
            }
        }
    }

    /** ⚡ 切后台：只停协程，绝不清空 duration 与 baseTimestampMs */
    fun pause() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /** ⚡ 切回前台：靠 (服务器当前时间 - 锚点时间戳) 瞬间补齐后台期间走过的进度 */
    fun resume() {
        if (durationMs > 0 && isPlaying) {
            tickOnce()
            start()
        }
    }

    /** 真正关房或歌曲彻底结束时调用：归零全部数据 */
    fun reset() {
        pause()
        durationMs = 0L
        basePositionMs = 0L
        baseTimestampMs = 0L
        playbackRate = 1.0
        isPlaying = false
        onProgressTick(0f)
    }

    private fun tickOnce() {
        if (durationMs <= 0) {
            onProgressTick(0f)
            return
        }

        val currentPosition = currentPositionMs()

        if (currentPosition >= durationMs && isPlaying) {
            onProgressTick(1f)
            onSongFinished()
            return
        }

        val ratio = (currentPosition.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        onProgressTick(ratio)
    }

    /** 按服务器时基推算的当前位置，单位毫秒 */
    fun currentPositionMs(): Long {
        if (!isPlaying || baseTimestampMs <= 0) return basePositionMs
        val serverNow = System.currentTimeMillis() + clockOffsetMs
        val elapsed = ((serverNow - baseTimestampMs) * playbackRate).toLong()
        val position = (basePositionMs + elapsed).coerceAtLeast(0L)
        // 主播端上报时已经按 durationMs 截断，这里再兜一次底，防止越界外推
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}
