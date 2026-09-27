package com.example.social_music.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.example.social_music.model.PlaybackPayload
import com.example.social_music.model.RoomInfo
import com.example.social_music.net.ApiConfig
import com.example.social_music.net.NeriRealtimeWatcher
import com.example.social_music.net.PushOutcome
import com.example.social_music.net.RoomApiService
import com.example.social_music.service.MusicSyncService
import com.example.social_music.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 房主端的一次实时播放快照。不可变，供界面与通知栏消费。
 *
 * 它取代了原来散在 MainActivity 里互相打架的 8 个 `live*` 字段 ——
 * 那些字段没法安全地跨 Activity 生命周期存活，而这份快照可以。
 */
data class LivePlayback(
    val songTitle: String,
    val artist: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val basePosMs: Long,
    val anchorAtMs: Long,
    val isPlaying: Boolean,
    val playbackRate: Double
) {
    /** 上报给服务端的歌名，格式沿用「歌名 - 歌手」 */
    val displaySong: String
        get() = if (artist.isNullOrBlank()) songTitle else "$songTitle - $artist"

    /** 从锚点外推的当前播放位置（与服务端同一套公式，含倍速） */
    fun positionMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isPlaying) return basePosMs
        val rate = if (playbackRate > 0) playbackRate else 1.0
        val elapsed = ((nowMs - anchorAtMs) * rate).toLong()
        val pos = (basePosMs + elapsed).coerceAtLeast(0L)
        return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
    }

    fun toPayload(): PlaybackPayload = PlaybackPayload(
        currentSong = displaySong,
        currentCover = coverUrl,
        durationMs = durationMs,
        basePositionMs = positionMs(),
        isPlaying = isPlaying,
        playbackRate = if (playbackRate > 0) playbackRate else 1.0
    )
}

/**
 * 房主会话：**进程级**持有 Neri 长连接、保活循环、状态推送和房主记录。
 *
 * 为什么必须搬出 Activity：这些东西原来都挂在 `lifecycleScope` 上，
 * Activity 一销毁（用户从最近任务划掉、或系统回收）就全断，
 * 房间随即变成僵尸，成员端再也收不到切歌 —— 这正是「通知栏前台服务」要解决的问题。
 *
 * 为什么是进程级单例、而不是放进 Service：Neri 连接是在**口令校验阶段**就建立的
 * （先确认房间真实存在，再让后端开房，避免幽灵房），而那时还没有「放歌」这回事，
 * 拉不起前台服务。所以会话必须能在没有 Service 的情况下存在；
 * 反过来，Service 的生命周期严格跟随 [hosting] 的起止。
 *
 * 线程约定：全部公开方法都在主线程调用。
 * NeriRealtimeWatcher 的回调本来就 post 到主 Looper，Service/Activity 调用也在主线程，
 * 所以这里用普通字段即可，不需要锁或 Flow。
 */
object HostSession {

    private const val TAG = "HostSession"

    /** 保活间隔。服务端 90 秒没收到上报就判定房主掉了，20 秒留足重试余地。 */
    private const val KEEPALIVE_INTERVAL_MS = 20_000L

    /**
     * 房主记录的有效期。超过这个时间就不再自动重新挂载房间 ——
     * 否则「昨天开过房、今天打开 App」会凭空拉起一个没人听的僵尸房间。
     */
    private const val RECORD_MAX_AGE_MS = 30 * 60 * 1000L

    /** 续写房主记录时间戳的最小间隔，避免每 20 秒就写一次 SharedPreferences */
    private const val RECORD_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    enum class StopReason { USER, ROOM_CLOSED, AUTH_EXPIRED, TIMEOUT, SERVICE_GONE }

    /** 界面侧订阅者（MainActivity）。只关心房主会话相关的事件，不是一个全局状态总线。 */
    interface Listener {
        /** 房主端零延迟画面：直接吃 Neri 回调，不等服务端回声 */
        fun onLivePlayback(playback: LivePlayback)

        /** 当前没有播放内容（Neri 报空） */
        fun onLiveCleared()

        /** 口令校验通过，房间真实存在 —— 上层可以去后端开房了 */
        fun onVerifyConnected()

        /** 校验阶段就发现房间已不存在（口令失效） */
        fun onVerifyRoomGone()

        /** 正式进入放歌状态 */
        fun onHostingStarted()

        /**
         * 服务端明确表示「这个用户已经没有房间了」（上报收到 404/403）。
         *
         * 这是唯一可信的「房间掉了」信号。快照里 room == null **不算** ——
         * pending 房间被点亮之前也是这个样子，拿它当依据会把刚开好的会话拆掉。
         */
        fun onRoomLost()

        /** 会话结束，界面该复位了 */
        fun onSessionStopped(reason: StopReason)

        /** 前台服务没能拉起来（系统限制等）：退化成「只有前台才同步」 */
        fun onForegroundServiceUnavailable() = Unit
    }

    /** 通知栏侧（MusicSyncService） */
    interface ServiceHooks {
        fun onEnterForeground(playback: LivePlayback?)
        fun onUpdateNotification(playback: LivePlayback?)
        fun onExitForeground()
    }

    private val api = RoomApiService()

    /**
     * 专用于「说再见」的作用域：它**绝不**随会话取消。
     * 用会话自己的 scope 去发 hostStop，会被取消自己取消掉，房间就留在服务端了。
     */
    private val byeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var listener: Listener? = null
    private var hooks: ServiceHooks? = null

    private var watcher: NeriRealtimeWatcher? = null
    private var pusher: HostStatePusher? = null
    private var sessionScope: CoroutineScope? = null
    private var keepaliveJob: Job? = null

    private var room: RoomInfo? = null
    private var live: LivePlayback? = null
    private var lastPushedSong: String? = null

    private var verifying = false
    private var hosting = false
    private var lastRecordRefreshAt = 0L

    /** 已经预热过的播放器服务器地址，避免重复发预热请求 */
    private val warmedServers = HashSet<String>()

    /** token 失效时置位，等 Activity 回到前台再消费（不能在后台弹登录页） */
    private var authExpired = false

    val isActive: Boolean get() = verifying || hosting
    val isHosting: Boolean get() = hosting
    val currentRoom: RoomInfo? get() = room
    val livePlayback: LivePlayback? get() = live

    // ==========================================================
    // 装配
    // ==========================================================

    @MainThread
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @MainThread
    fun setListener(value: Listener?) {
        listener = value
    }

    @MainThread
    fun bindHooks(value: ServiceHooks?) {
        hooks = value
    }

    /** 取出并清除「token 已失效」标记 */
    @MainThread
    fun consumeAuthExpired(): Boolean {
        val v = authExpired
        authExpired = false
        return v
    }

    // ==========================================================
    // 口令校验
    // ==========================================================

    /**
     * 预热到播放器服务器的连接。
     *
     * 开房那一次 join 是新建连接，握手要 0.5~5.7 秒，全砸在用户点「开启」之后。
     * 用户粘完口令到真正点下去之间往往有一两秒，这里先把 TCP+TLS 建好，
     * 那笔钱就不用他付了。**不 join 房间**，没有任何业务副作用。
     *
     * 幂等：同一个 serverUrl 只预热一次，免得每敲一个字符就发一次请求。
     */
    @MainThread
    fun preconnectNeri(serverUrl: String?) {
        val ctx = appContext ?: return
        val url = serverUrl?.trim().orEmpty().ifEmpty { ApiConfig.DEFAULT_NERI_SERVER }
        if (!warmedServers.add(url)) return

        val w = watcher ?: NeriRealtimeWatcher(
            context = ctx,
            onPlaybackUpdate = { title, artist, cover, duration, basePos, _, rate, playing ->
                onNeriPlayback(title, artist, cover, duration, basePos, rate, playing)
            },
            onConnected = { onNeriConnected() },
            onRoomClosed = { onNeriRoomClosed() }
        ).also { watcher = it }

        w.preconnect(url)
    }

    /**
     * 进入口令校验：建立 Neri 长连接，用它来确认「口令有效且房间真实存在」。
     * 这条连接在校验通过后会**原样复用**成放歌用的那条，不会多出一个机器人。
     */
    @MainThread
    fun beginVerification(info: RoomInfo) {
        if (isActive) {
            Log.w(TAG, "已有会话在进行，忽略重复的 beginVerification")
            return
        }
        room = info
        verifying = true
        startWatcher(info)
    }

    /**
     * 校验失败/超时：收掉 Neri 连接，会话结束。
     *
     * 刻意不碰服务端、也不碰前台服务 —— 此阶段前台服务根本还没起来，
     * 而「撤销可能已挂出的 pending 房间」由发起那次开房的人负责
     * （见 MainActivity 的 startJob：它落地时发现校验已失败才会 hostStop，
     *  这样才能保证撤销一定排在开房之后）。
     * 也刻意不回调 listener：这条路径由调用方自己收尾界面，避免重复处理。
     */
    @MainThread
    fun cancelVerification() {
        if (!verifying) return
        verifying = false
        watcher?.stopWatching()
        room = null
        live = null
        lastPushedSong = null
    }

    // ==========================================================
    // 正式放歌
    // ==========================================================

    /**
     * 后端已确认房间归我。从这里开始才是「放歌」，前台服务也在这时拉起。
     * 幂等：重复调用（Activity 重建、断线重挂）只会走一次。
     */
    @MainThread
    fun commitHosting(info: RoomInfo) {
        if (hosting) {
            Log.w(TAG, "已经在放歌，忽略重复的 commitHosting")
            return
        }

        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "HostSession 未初始化，无法进入放歌状态")
            return
        }

        room = info
        verifying = false
        hosting = true

        // 校验阶段的那条连接会被复用（同房间号直接返回），这里只是兜底
        startWatcher(info)

        persistRoom(info)
        startSessionWork()
        confirmToServer()

        // 校验阶段缓存下来的首帧现在补上，否则要等到下一次切歌才有画面
        live?.let { listener?.onLivePlayback(it) }
        listener?.onHostingStarted()
    }

    /**
     * 结束会话。所有退出路径（用户关播、登出、服务端关房、token 失效、
     * 前台服务超时）都必须走这里，只有一处收口才不会漏。
     */
    @MainThread
    fun stop(notifyServer: Boolean, reason: StopReason) {
        if (!isActive) return

        val wasHosting = hosting
        val ctx = appContext
        val token = ctx?.let { SessionManager(it).getToken() }
        // 关房必须锁定到具体房间号：不然一条迟到的 stop 会把用户随后刚开好的新房关掉
        val closingRoomId = room?.roomId

        verifying = false
        hosting = false

        teardownSessionWork()
        watcher?.stopWatching()

        room = null
        live = null
        lastPushedSong = null
        lastRecordRefreshAt = 0L
        if (ctx != null) SessionManager(ctx).clearHostingRoom()

        // 用不会被会话取消的 scope 去说再见
        if (wasHosting && notifyServer && !token.isNullOrEmpty()) {
            byeScope.launch { api.hostStop(token, closingRoomId) }
        }

        if (reason == StopReason.AUTH_EXPIRED) authExpired = true

        hooks?.onExitForeground()
        stopForegroundService()
        listener?.onSessionStopped(reason)
    }

    /** 拆掉会话内的后台工作（保活、推送、会话作用域）。不碰房间和播放状态。 */
    private fun teardownSessionWork() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        pusher?.stop()
        pusher = null
        sessionScope?.cancel()
        sessionScope = null
    }

    /**
     * 重新挂载到同一个房间，但**保留**当前播放状态。
     *
     * 和 [stop] 的区别是关键：[stop] 会把 [live] 清空，而重挂时歌还在放 ——
     * 服务端丢的只是那条房间记录，本地播放状态一点没失效。
     *
     * 清掉它的后果很隐蔽但很致命：房间重新上线后是**没有歌**的，而 NeriPlayer
     * 只在状态**变化**时才推，所以没有任何东西会把这歌补回来 ——
     * 用户会一直看到「你正在放歌」却不见歌曲，直到自己手动切一首。
     *
     * 也不重连 Neri：那条长连接是连**播放器**的，跟后端这条房间记录没关系，
     * 全程都是好的。停掉再连只会白白多一次 1~7 秒的 join。
     */
    @MainThread
    fun reattach(info: RoomInfo) {
        val ctx = appContext ?: return

        room = info
        verifying = false
        hosting = true

        // 幂等：同房间同连接时直接返回，不会重复 join
        startWatcher(info)
        persistRoom(info)

        if (sessionScope == null) {
            // 会话工作之前被拆掉了（比如服务被系统回收过），整套补起来
            startSessionWork()
        } else {
            startForegroundService()
        }

        // 立刻把当前这首歌推上去：房间一上线就有画面，不用干等 Neri 的下一次事件
        confirmToServer()
        listener?.onHostingStarted()
    }

    /**
     * 把「房间已经确认归我」告诉服务端。
     *
     * 这一条同时干两件事：点亮那个 pending 占位房间（让它对成员可见），
     * 以及把当前首帧播放状态送上去。两者合成一次往返，不额外多花时间。
     */
    @MainThread
    private fun confirmToServer() {
        val payload = live?.toPayload()
        pusher?.push(payload, confirm = true, urgent = true)
        hooks?.onUpdateNotification(live)
    }

    // ==========================================================
    // Neri 回调
    // ==========================================================

    private fun startWatcher(info: RoomInfo) {
        val ctx = appContext ?: return
        val roomId = info.roomId
        val secret = info.secret
        if (roomId.isNullOrEmpty() || secret.isNullOrEmpty()) return

        val w = watcher ?: NeriRealtimeWatcher(
            context = ctx,
            onPlaybackUpdate = { title, artist, cover, duration, basePos, _, rate, playing ->
                onNeriPlayback(title, artist, cover, duration, basePos, rate, playing)
            },
            onConnected = { onNeriConnected() },
            onRoomClosed = { onNeriRoomClosed() }
        ).also { watcher = it }

        w.startWatching(
            info.serverUrl ?: ApiConfig.DEFAULT_NERI_SERVER,
            roomId,
            secret,
            SessionManager(ctx).getUsername()
        )
    }

    private fun onNeriConnected() {
        if (!isActive) return

        if (verifying) {
            // 口令有效、房间真实存在 —— 交给上层去后端开房
            listener?.onVerifyConnected()
            return
        }

        // 放歌途中的重连：重新确认一次，顺带补上服务端可能缺掉的首帧
        if (hosting) confirmToServer()
    }

    private fun onNeriRoomClosed() {
        if (verifying) {
            listener?.onVerifyRoomGone()
            return
        }
        if (hosting) {
            // ⚠️ 必须通知服务端，否则那条房间记录会一直挂到 90 秒心跳超时才回收，
            // 而这段时间服务端仍然声称「有人在放歌」。
            stop(notifyServer = true, reason = StopReason.ROOM_CLOSED)
        }
    }

    private fun onNeriPlayback(
        songTitle: String?,
        artist: String?,
        coverUrl: String?,
        durationMs: Long,
        basePosMs: Long,
        playbackRate: Double,
        isPlaying: Boolean
    ) {
        if (!isActive) return

        if (songTitle.isNullOrBlank()) {
            live = null
            lastPushedSong = null
            if (hosting) {
                pusher?.push(null)
                hooks?.onUpdateNotification(null)
                listener?.onLiveCleared()
            }
            return
        }

        val previous = live
        val playback = LivePlayback(
            songTitle = songTitle,
            artist = artist,
            coverUrl = coverUrl,
            durationMs = durationMs,
            basePosMs = basePosMs,
            // 用本机收到回调的时刻做锚点，天然把网络延迟算进去了
            anchorAtMs = System.currentTimeMillis(),
            isPlaying = isPlaying,
            playbackRate = playbackRate
        )
        live = playback

        // 校验阶段只缓存首帧：画面与上报都等开房成功之后
        if (verifying) return

        // 切歌 / 播放暂停是"必须立刻到"的事件；纯进度上报可以合并丢弃
        val urgent = songTitle != lastPushedSong || previous?.isPlaying != isPlaying
        lastPushedSong = songTitle

        pusher?.push(playback.toPayload(), urgent = urgent)
        hooks?.onUpdateNotification(playback)
        listener?.onLivePlayback(playback)
    }

    // ==========================================================
    // 会话内的后台工作
    // ==========================================================

    private fun startSessionWork() {
        val scope = sessionScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .also { sessionScope = it }

        // 每次会话都用全新的推送器：它的 scope 必须和本次会话同生死，
        // 否则上一代的 scope 被取消后，新的推送会静静地什么都不做。
        pusher = HostStatePusher(
            scope = scope,
            tokenProvider = { appContext?.let { SessionManager(it).getToken() } },
            onOutcome = { onPushOutcome(it) }
        )

        startKeepalive(scope)
        startForegroundService()
    }

    private fun startKeepalive(scope: CoroutineScope) {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob = scope.launch {
            while (isActive && hosting) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (hosting) pusher?.push(live?.toPayload())
            }
        }
    }

    private fun onPushOutcome(outcome: PushOutcome) {
        when (outcome) {
            PushOutcome.Ok -> touchRecord()
            PushOutcome.Stale -> Log.i(TAG, "服务端判定本次上报迟到，已丢弃")
            PushOutcome.RoomLost -> {
                // 服务端明确说这个用户已经没有房间了。把这个信号交给上层，
                // 由它决定要不要重挂 —— 这是唯一可信的「房间掉了」依据。
                Log.w(TAG, "服务端已无本房间，上报上层等待重新挂载")
                listener?.onRoomLost()
            }
            PushOutcome.Unauthorized -> stop(notifyServer = false, reason = StopReason.AUTH_EXPIRED)
            is PushOutcome.Failed -> Unit
        }
    }

    private fun startForegroundService() {
        val ctx = appContext ?: return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MusicSyncService::class.java))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException 等。
            // 不致命：退化成「只有 App 在前台时才可靠同步」，但不该让整条流程挂掉。
            Log.w(TAG, "前台服务启动失败，退化为仅前台同步: ${e.message}")
            listener?.onForegroundServiceUnavailable()
        }
    }

    private fun stopForegroundService() {
        val ctx = appContext ?: return
        runCatching { ctx.stopService(Intent(ctx, MusicSyncService::class.java)) }
    }

    // ==========================================================
    // 房主记录持久化（进程被杀后的恢复依据）
    // ==========================================================

    private fun persistRoom(info: RoomInfo) {
        val ctx = appContext ?: return
        val session = SessionManager(ctx)
        val json = JSONObject().apply {
            put("ownerQq", session.getQq())
            put("owner", session.getUsername())
            put("roomId", info.roomId ?: "")
            put("secret", info.secret ?: "")
            put("deepLink", info.rawUri)
            put("serverUrl", info.serverUrl ?: ApiConfig.DEFAULT_NERI_SERVER)
            put("inviter", info.inviter ?: "")
            put("savedAt", System.currentTimeMillis())
        }
        lastRecordRefreshAt = System.currentTimeMillis()
        session.saveHostingRoom(json.toString())
    }

    /** 还在正常放歌时定期把记录时间戳续上，否则它会在 30 分钟后过期 */
    private fun touchRecord() {
        val now = System.currentTimeMillis()
        if (now - lastRecordRefreshAt < RECORD_REFRESH_INTERVAL_MS) return
        room?.let { persistRoom(it) }
    }

    /**
     * 读回上次的房主身份。换了账号、或记录已过期都会作废并返回 null。
     */
    @MainThread
    fun restorePersistedRoom(): RoomInfo? {
        val ctx = appContext ?: return null
        val session = SessionManager(ctx)
        val raw = session.getHostingRoom() ?: return null

        return try {
            val json = JSONObject(raw)

            // 换了账号登录，旧的房主身份必须作废。
            // 用 qq 而不是昵称比对 —— 昵称可以随时改，改了不该把房间丢掉。
            val ownerQq = json.optString("ownerQq", "")
            val myQq = session.getQq()
            if (ownerQq.isNotEmpty() && myQq.isNotEmpty() && ownerQq != myQq) {
                session.clearHostingRoom()
                return null
            }

            val savedAt = json.optLong("savedAt", 0L)
            if (savedAt > 0 && System.currentTimeMillis() - savedAt > RECORD_MAX_AGE_MS) {
                Log.i(TAG, "房主记录已过期，作废")
                session.clearHostingRoom()
                return null
            }

            val roomId = json.optString("roomId", "")
            if (roomId.isEmpty()) return null

            RoomInfo(
                rawUri = json.optString("deepLink", ""),
                roomId = roomId,
                inviter = json.optString("inviter", "").ifEmpty { null },
                secret = json.optString("secret", "").ifEmpty { null },
                serverUrl = json.optString("serverUrl", "").ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }
}
