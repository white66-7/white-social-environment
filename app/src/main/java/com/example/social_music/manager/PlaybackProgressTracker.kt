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

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        onProgressTick(0f)
    }

    private fun tickOnce() {
        if (durationMs <= 0) {
            onProgressTick(0f)
            return
        }

        val now = System.currentTimeMillis()
        val currentPosition = if (isPlaying && baseTimestampMs > 0) {
            val elapsed = ((now - baseTimestampMs) * playbackRate).toLong()
            (basePositionMs + elapsed).coerceAtLeast(0L)
        } else {
            basePositionMs
        }

        if (currentPosition >= durationMs && isPlaying) {
            onProgressTick(1f)
            onSongFinished()
            return
        }

        val ratio = (currentPosition.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        onProgressTick(ratio)
    }
}