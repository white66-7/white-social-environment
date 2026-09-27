package com.example.social_music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.social_music.MainActivity
import com.example.social_music.R
import com.example.social_music.manager.HostSession
import com.example.social_music.manager.LivePlayback

/**
 * 开播期间常驻的前台服务：**纯粹是为了不让放歌会话被系统杀掉**。
 *
 * 房主的实时状态靠一条 Neri 长连接 + 每 20 秒一次的 HTTP 保活维持，
 * 服务端 90 秒收不到上报就判定房主掉线并关房。而这两样东西以前都挂在
 * Activity 的 `lifecycleScope` 上 —— 用户把 App 从最近任务划掉，连接就断了，
 * 成员端从此再也收不到切歌。
 *
 * 真正的活儿在 [HostSession] 里；这个服务负责的是**进程存活 + 通知栏可见**，
 * 两者生命周期严格绑定：开播即拉起，关播即停止。
 *
 * 服务类型用 `dataSync`（见 AndroidManifest）。注意 Android 15+ 对它有
 * 每天 6 小时的累计上限，到点系统会回调 [onTimeout] —— 那时**必须几秒内 stopSelf()**，
 * 否则会抛致命的 RemoteServiceException。
 */
class MusicSyncService : Service(), HostSession.ServiceHooks {

    companion object {
        private const val TAG = "MusicSyncService"
        private const val CHANNEL_ID = "music_sync"
        private const val NOTIF_ID = 1001

        /** 通知栏上的「停止放歌」 */
        const val ACTION_STOP = "com.example.social_music.action.STOP_HOSTING"

        /**
         * 唤醒锁的兜底超时。取和系统 dataSync 前台服务同一个量级（6 小时），
         * 就算哪里漏了释放也不会一直耗电。
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }

    private var foregroundStarted = false

    /**
     * 息屏期间保住 CPU。
     *
     * 保活循环是协程里的 `delay(20_000)`，而它基于 SystemClock.uptimeMillis ——
     * **设备深睡时这个时钟停止推进**，于是 20 秒的间隔在墙上时间会被拉长到几分钟，
     * 服务端 90 秒收不到上报就把房间关了。前台服务能保住进程，但保不住 CPU 时钟，
     * 这两件事是分开的。所以必须在开播期间持有这把锁。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        HostSession.init(applicationContext)
        HostSession.bindHooks(this)
        createChannel()

        // 必须在这里同步调到 startForeground —— 系统给 startForegroundService()
        // 之后只有约 5 秒的窗口，超时会抛 ForegroundServiceDidNotStartInTimeException。
        // 通知内容直接用 HostSession 已缓存的快照，不等待任何网络请求。
        enterForeground(HostSession.livePlayback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "用户从通知栏停止放歌")
            HostSession.stop(notifyServer = true, reason = HostSession.StopReason.USER)
            // 兜底停服务：通知点击可能被延迟投递到会话早就结束之后，
            // 那时 HostSession.stop 会在 isActive 检查处直接返回、不回调
            // onExitForeground —— 于是 onCreate 刚 startForeground 起来的服务
            // 就变成了一个没有会话的孤儿，一直挂到系统的 6 小时时限。
            exitForeground()
            return START_NOT_STICKY
        }

        // 会话已经不在（例如被别处停掉）就别赖着，否则就是一个空转的前台服务
        if (!HostSession.isHosting) {
            exitForeground()
            return START_NOT_STICKY
        }

        enterForeground(HostSession.livePlayback)
        return START_NOT_STICKY
    }

    /**
     * Android 15+ 的 dataSync 时长上限（6 小时/24 小时）用尽时回调。
     *
     * 这里没有选择：系统只给几秒钟，不主动停就抛
     * `RemoteServiceException: A foreground service of type dataSync did not stop within its timeout`，
     * 直接崩掉整个进程。所以先收干净会话，再停服务。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务已达系统时限（dataSync 6 小时上限），主动收尾")
        HostSession.stop(notifyServer = true, reason = HostSession.StopReason.TIMEOUT)
        exitForeground()
    }

    override fun onDestroy() {
        releaseWakeLock()
        HostSession.bindHooks(null)

        // 防御：服务被系统或用户干掉时，别留下一个还在跑会话却没有前台服务的状态 ——
        // 那种情况下进程随时可能被杀，保活循环的存在反而是误导。
        if (HostSession.isHosting) {
            Log.w(TAG, "服务被销毁但会话仍在，一并收尾")
            HostSession.stop(notifyServer = false, reason = HostSession.StopReason.SERVICE_GONE)
        }

        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==========================================================
    // HostSession.ServiceHooks
    // ==========================================================

    override fun onEnterForeground(playback: LivePlayback?) = enterForeground(playback)

    override fun onUpdateNotification(playback: LivePlayback?) {
        if (!foregroundStarted) return
        notifySafely(buildNotification(playback))
    }

    override fun onExitForeground() = exitForeground()

    // ==========================================================
    // 前台状态
    // ==========================================================

    private fun enterForeground(playback: LivePlayback?) {
        if (foregroundStarted) {
            notifySafely(buildNotification(playback))
            return
        }

        val notification = buildNotification(playback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foregroundStarted = true

        // 前台服务一建立就把 CPU 锁上，息屏后保活才能按点跑
        acquireWakeLock()
    }

    private fun exitForeground() {
        releaseWakeLock()
        // minSdk 24，STOP_FOREGROUND_REMOVE 一直可用
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "social_music:hosting").apply {
            // 关播时显式释放，所以不要引用计数
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.i(TAG, "已持有唤醒锁，息屏后保活不会被系统睡眠拖慢")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
            Log.i(TAG, "已释放唤醒锁")
        }
        wakeLock = null
    }

    private fun notifySafely(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // API 33+ 用户拒绝了 POST_NOTIFICATIONS。
            // 前台服务照常运行，只是通知栏里看不到 —— 不影响放歌同步。
            Log.w(TAG, "没有通知权限，跳过通知更新: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.sync_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(playback: LivePlayback?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            // FLAG_IMMUTABLE 是 API 31+ 的硬性要求
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 应用里没有别的关播入口，通知栏这个是用户唯一能主动停下来的地方
        val stopHosting = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            playback != null -> playback.displaySong
            HostSession.isHosting -> getString(R.string.sync_notification_connecting)
            else -> getString(R.string.sync_notification_idle)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.sync_notification_stop), stopHosting)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
