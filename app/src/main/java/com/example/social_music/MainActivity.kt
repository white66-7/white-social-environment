package com.example.social_music

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.social_music.manager.PlaybackProgressTracker
import com.example.social_music.model.ActiveRoom
import com.example.social_music.model.MemberInfo
import com.example.social_music.model.PlaybackPayload
import com.example.social_music.model.RoomInfo
import com.example.social_music.model.RoomSnapshot
import com.example.social_music.net.ApiConfig
import com.example.social_music.net.NeriRealtimeWatcher
import com.example.social_music.net.ProfileOutcome
import com.example.social_music.net.PushOutcome
import com.example.social_music.net.RoomApiService
import com.example.social_music.net.RoomRealtimeClient
import com.example.social_music.net.StartOutcome
import com.example.social_music.net.StateOutcome
import com.example.social_music.utils.AnimationTemplates
import com.example.social_music.utils.CapsuleTipManager
import com.example.social_music.utils.NeriDeepLinkHelper
import com.example.social_music.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 单一权威状态来源：服务端快照。
 *
 * 这次重构删掉了两样东西：
 *  1. 6~10 秒一轮的房间状态轮询 —— 改成 RoomRealtimeClient 的一条长连接推送；
 *  2. 用一堆散落的布尔标志位（isHosting / currentDeepLink / roomMissCount /
 *     currentSongTimestamp…）互相打架来推断界面 —— 改成 render(snapshot) 单向渲染。
 *
 * 界面结构与控件完全沿用原有布局，没有改动 UI。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_HOST_REVIVE = 3
        private const val HOST_KEEPALIVE_INTERVAL_MS = 20_000L

        /**
         * 房主记录的有效期。超过这个时间就不再自动重新挂载房间 ——
         * 否则「昨天开过房、今天打开 App」会凭空拉起一个没人听的僵尸房间。
         */
        private const val HOST_RECORD_MAX_AGE_MS = 30 * 60 * 1000L

        /** 刷新房主记录时间戳的最小间隔，避免每 20 秒就写一次 SharedPreferences */
        private const val RECORD_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        /** 花名册缓存时长：名单很少变，在线角标走长连接实时刷新，不必频繁重拉 */
        private const val ROSTER_CACHE_MS = 30_000L

        /** 主动关房后的静默窗口，用来吞掉自己触发的那条 room_closed 提示 */
        private const val ROOM_CLOSED_SUPPRESS_MS = 5_000L

        /**
         * 校验邀请口令的超时时间。
         * 超过这么久播放器服务器还没确认 join 成功，就认为口令已失效或服务不可达，
         * 直接放弃开房 —— 绝不在后端留下一个指向不存在房间的幽灵房。
         */
        private const val SECRET_VERIFY_TIMEOUT_MS = 12_000L
    }

    // ---------- 依赖 ----------
    private val api = RoomApiService()
    private lateinit var sessionManager: SessionManager
    private lateinit var tipManager: CapsuleTipManager
    private lateinit var progressTracker: PlaybackProgressTracker
    private lateinit var realtime: RoomRealtimeClient

    /** 只在房主身份下启用：连 NeriPlayer 感知切歌。成员端绝不连，避免房间里多出机器人 */
    private lateinit var neriWatcher: NeriRealtimeWatcher

    // ---------- 权威状态 ----------
    private var snapshot: RoomSnapshot? = null

    /**
     * 快照排序依据，独立于 snapshot 保存。
     * room_closed 只带版本号不带快照，如果直接写回 snapshot.version，
     * 一条迟到的 REST 快照就能用同一个版本号把已经关掉的房间又画回来。
     */
    private var lastVersion = 0L
    private var hasRenderedOnce = false
    private var wasLoggedIn = false

    // ---------- 花名册（全部注册成员）----------
    private val roster = mutableListOf<MemberInfo>()
    private var rosterLoaded = false
    private var rosterError: String? = null
    private var rosterJob: Job? = null
    private var lastRosterFetchAt = 0L

    // 实时状态：由长连接快照刷新，用来给花名册打角标
    private var onlineQq: Set<String> = emptySet()
    private var hostQq: String? = null

    // ---------- 房主本地状态 ----------
    /** 本地是否正以房主身份向服务器上报（决定是否维持 Neri 长连接与保活循环） */
    private var isHosting = false
    private var isPublishing = false
    private var isRevivingHost = false
    private var hostReviveCount = 0
    private var currentRoomInfo: RoomInfo? = null
    private var lastRecordRefreshAt = 0L

    /** 主动关房后的静默截止时间 */
    private var suppressRoomClosedUntil = 0L

    // ---------- 开房前的口令校验 ----------
    // 流程是「先连播放器确认房间真实存在，再让后端开房」。
    // 以前的顺序反了，口令失效时会先在后端开出一个幽灵房。
    private var isVerifyingSecret = false
    private var pendingRoomInfo: RoomInfo? = null
    private var verifyJob: Job? = null

    // 房主端渲染用的是 Neri 回调的本地数据，零延迟
    private var liveSongTitle: String? = null
    private var liveArtist: String? = null
    private var liveCoverUrl: String? = null
    private var liveDurationMs = 0L
    private var liveBasePosMs = 0L
    private var liveAnchorAtMs = 0L
    private var liveIsPlaying = false
    private var livePlaybackRate = 1.0

    // ---------- 任务 ----------
    private var bootstrapJob: Job? = null
    private var hostKeepaliveJob: Job? = null

    // 全局顶部控件
    private lateinit var ivUserAvatar: ShapeableImageView
    private lateinit var tvCurrentUserName: TextView
    private lateinit var btnLogout: TextView

    // Tab 容器与按钮
    private lateinit var layoutMusicTabContent: View
    private lateinit var layoutMembersTabContent: View
    private lateinit var tabMusic: View
    private lateinit var tabMembers: View
    private lateinit var tvTabMusic: TextView
    private lateinit var tvTabMembers: TextView

    // 听歌主界面控件
    private lateinit var webHexLoader: WebView
    private lateinit var ivHostAvatar: ShapeableImageView
    private lateinit var tvHostMessage: TextView

    // 播放器卡片控件
    private lateinit var layoutAudioPlayerCard: View
    private lateinit var ivPlayerAlbumCover: ShapeableImageView
    private lateinit var tvPlayerSongTitle: TextView
    private lateinit var tvPlayerArtist: TextView
    private lateinit var pbPlayerProgress: ProgressBar

    private lateinit var btnJoin: MaterialButton
    private lateinit var layoutHostSection: View
    private lateinit var btnPasteInvite: TextView
    private lateinit var etInviteCode: EditText
    private lateinit var btnPublishRoom: MaterialButton

    // 成员主界面控件
    private lateinit var tvMemberCountBadge: TextView
    private lateinit var layoutMembersContainer: LinearLayout
    private var webMembersLoader: WebView? = null

    // ==========================================================
    // 生命周期
    // ==========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = SessionManager(this)
        tipManager = CapsuleTipManager(this)
        wasLoggedIn = sessionManager.isLoggedIn()

        initViews()
        initWebViewSettings()
        initProgressTracker()
        initRealtime()
        initNeriWatcher()
        setupListeners()

        renderInitialState()
        if (wasLoggedIn) bootstrap()
    }

    override fun onStart() {
        super.onStart()
        connectRealtimeIfPossible()
    }

    override fun onResume() {
        super.onResume()
        updateUserUi()

        // ⚡ 切回前台立刻按服务器时基补齐后台期间走过的进度
        progressTracker.resume()

        val loggedInNow = sessionManager.isLoggedIn()
        if (loggedInNow != wasLoggedIn) {
            wasLoggedIn = loggedInNow
            if (loggedInNow) onLoggedIn() else onLoggedOut()
        }

        if (loggedInNow) {
            connectRealtimeIfPossible()
            refreshMembersTab()
        }
    }

    override fun onStop() {
        super.onStop()

        // 实时通道只服务于前台画面，切后台断开省电。
        // 房主的状态上报走 HTTP，不受影响；而且服务端会强制把房主留在成员列表里。
        realtime.disconnect()

        progressTracker.pause()

        // 校验校验到一半切后台：直接放弃本次校验，别留个半吊子状态
        if (isVerifyingSecret) {
            cancelSecretVerification()
        }

        // 房主在放歌时，切到外部播放器切歌绝不能断开 Neri 长连接
        if (!isHosting) {
            neriWatcher.stopWatching()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 刻意不在这里关房、也不清房主持久化记录：
        // 转屏或 Activity 回收不该把正在放歌的房间打掉，
        // 真正的兜底是服务端 90 秒心跳超时。
        hostKeepaliveJob?.cancel()
        bootstrapJob?.cancel()
        verifyJob?.cancel()
        neriWatcher.stopWatching()
        realtime.disconnect()

        (webHexLoader.parent as? ViewGroup)?.removeView(webHexLoader)
        webHexLoader.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        webHexLoader.clearHistory()
        webHexLoader.destroy()

        webMembersLoader?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            it.clearHistory()
            it.destroy()
        }
        webMembersLoader = null
    }

    // ==========================================================
    // 初始化
    // ==========================================================

    private fun initProgressTracker() {
        progressTracker = PlaybackProgressTracker(
            scope = lifecycleScope,
            onProgressTick = { ratio ->
                pbPlayerProgress.progress = (ratio * 1000).toInt()
            },
            onSongFinished = {}
        )
    }

    private fun initRealtime() {
        realtime = RoomRealtimeClient(object : RoomRealtimeClient.Listener {

            override fun onSnapshot(incoming: RoomSnapshot) {
                applySnapshot(incoming)
            }

            override fun onRoomClosed(reason: String, version: Long) {
                handleServerRoomClosed(reason, version)
            }

            override fun onConnected() = Unit

            override fun onDisconnected(reason: String?) {
                // 断线只影响新鲜度，绝不清空画面 —— 重连后会收到全量快照自动收敛。
                // 清空才是「成员一会儿一个一会儿两个」那类抖动的来源。
                if (reason == "登录状态已失效") {
                    forceReLogin("登录状态已失效，请重新登录")
                }
            }
        })
    }

    private fun initNeriWatcher() {
        neriWatcher = NeriRealtimeWatcher(
            context = this,
            onPlaybackUpdate = { songTitle, artist, coverUrl, durationMs, basePosMs, baseTimestampMs, playbackRate, isPlaying ->
                onHostPlaybackUpdate(
                    songTitle, artist, coverUrl, durationMs,
                    basePosMs, playbackRate, isPlaying
                )
            },
            onConnected = { onNeriConnected() },
            onRoomClosed = {
                // 只有房主会连 Neri，所以这里一定是「我自己的房间没了」
                if (isVerifyingSecret) {
                    // 校验阶段就失败 = 口令已失效，绝不能再去后端开房
                    cancelSecretVerification()
                    showTip("邀请口令已失效，房间已不存在")
                } else if (isHosting) {
                    // ⚠️ 必须通知后端。以前这里是 notifyServer = false，
                    // 后端那条房间记录会一直挂到 90 秒心跳超时才回收；
                    // 而这段时间服务端仍然声称「你正在放歌」，界面会被快照拽回去，
                    // 看起来就是「房间关不掉」。
                    stopHosting(notifyServer = true)
                    showTip("房主已结束放歌")
                }
            }
        )
    }

    private fun initViews() {
        ivUserAvatar = findViewById(R.id.ivUserAvatar)
        tvCurrentUserName = findViewById(R.id.tvCurrentUserName)
        btnLogout = findViewById(R.id.btnLogout)

        layoutMusicTabContent = findViewById(R.id.layoutMusicTabContent)
        layoutMembersTabContent = findViewById(R.id.layoutMembersTabContent)
        tabMusic = findViewById(R.id.tabMusic)
        tabMembers = findViewById(R.id.tabMembers)
        tvTabMusic = findViewById(R.id.tvTabMusic)
        tvTabMembers = findViewById(R.id.tvTabMembers)

        webHexLoader = findViewById(R.id.webHexLoader)
        ivHostAvatar = findViewById(R.id.ivHostAvatar)
        tvHostMessage = findViewById(R.id.tvHostMessage)

        layoutAudioPlayerCard = findViewById(R.id.layoutAudioPlayerCard)
        ivPlayerAlbumCover = findViewById(R.id.ivPlayerAlbumCover)
        tvPlayerSongTitle = findViewById(R.id.tvPlayerSongTitle)
        tvPlayerArtist = findViewById(R.id.tvPlayerArtist)
        pbPlayerProgress = findViewById(R.id.pbPlayerProgress)
        pbPlayerProgress.max = 1000

        btnJoin = findViewById(R.id.btnJoin)
        layoutHostSection = findViewById(R.id.layoutHostSection)
        btnPasteInvite = findViewById(R.id.btnPasteInvite)
        etInviteCode = findViewById(R.id.etInviteCode)
        btnPublishRoom = findViewById(R.id.btnPublishRoom)

        tvMemberCountBadge = findViewById(R.id.tvMemberCountBadge)
        layoutMembersContainer = findViewById(R.id.layoutMembersContainer)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewSettings() {
        webHexLoader.setBackgroundColor(0)
        webHexLoader.settings.javaScriptEnabled = true
        webHexLoader.settings.domStorageEnabled = true
        webHexLoader.isVerticalScrollBarEnabled = false
        webHexLoader.isHorizontalScrollBarEnabled = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateMembersLoader(): WebView {
        val existing = webMembersLoader
        if (existing != null) return existing

        val webView = WebView(this).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        webMembersLoader = webView
        return webView
    }

    private fun setupListeners() {
        tabMusic.setOnClickListener { switchTab(isMusicTab = true) }
        tabMembers.setOnClickListener {
            switchTab(isMusicTab = false)
            refreshMembersTab()
        }

        ivUserAvatar.setOnClickListener {
            if (sessionManager.isLoggedIn()) {
                startActivitySafely("com.example.social_music.SettingsActivity")
            } else {
                startActivitySafely("com.example.social_music.LoginActivity")
            }
        }

        btnLogout.setOnClickListener {
            if (sessionManager.isLoggedIn()) {
                logout()
            } else {
                startActivitySafely("com.example.social_music.LoginActivity")
            }
        }

        btnPasteInvite.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString().orEmpty()
                etInviteCode.setText(text)
                etInviteCode.setSelection(text.length)
                showTip("已粘贴口令")
            } else {
                showTip("剪贴板为空")
            }
        }

        btnJoin.setOnClickListener {
            if (!requireLogin()) return@setOnClickListener

            val link = snapshot?.room?.deepLink
            if (link.isNullOrEmpty()) {
                showTip("当前无可用房间链接")
                return@setOnClickListener
            }
            verifyAndJoinRoom(link)
        }

        btnPublishRoom.setOnClickListener {
            if (!requireLogin()) return@setOnClickListener
            if (isPublishing) return@setOnClickListener

            val text = etInviteCode.text.toString().trim()
            val roomInfo = NeriDeepLinkHelper.parseInvitation(text)
            if (roomInfo == null || roomInfo.roomId.isNullOrEmpty()) {
                showTip("未识别出合法的 NeriPlayer 邀请口令！")
                return@setOnClickListener
            }

            verifyAndStartHosting(roomInfo)
        }
    }

    // ==========================================================
    // 会话
    // ==========================================================

    private fun requireLogin(): Boolean {
        if (sessionManager.isLoggedIn()) return true
        showTip("请先登录")
        startActivitySafely("com.example.social_music.LoginActivity")
        return false
    }

    private fun logout() {
        stopHosting(notifyServer = true)
        sessionManager.clearSession()
        realtime.disconnect()
        resetSessionState()
        wasLoggedIn = false
        updateUserUi()
        renderInitialState()
        showTip("已退出登录")
    }

    private fun onLoggedIn() {
        hostReviveCount = 0
        resetSessionState()
        renderInitialState()
        bootstrap()
    }

    private fun onLoggedOut() {
        stopHosting(notifyServer = true)
        realtime.disconnect()
        resetSessionState()
        renderInitialState()
    }

    private fun forceReLogin(message: String) {
        stopHosting(notifyServer = false)
        sessionManager.clearSession()
        realtime.disconnect()
        resetSessionState()
        wasLoggedIn = false
        updateUserUi()
        renderInitialState()
        showTip(message)
    }

    /**
     * 清掉所有跟上一次登录身份绑定的内存状态。
     * 必须连在途请求一起取消 —— 否则退出登录后，一个还在飞的 fetchState
     * 会回来把房间画面重新画到「未登录」界面上面。
     */
    private fun resetSessionState() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        rosterJob?.cancel()
        rosterJob = null

        roster.clear()
        rosterLoaded = false
        rosterError = null
        lastRosterFetchAt = 0L

        onlineQq = emptySet()
        hostQq = null
        lastVersion = 0L
        snapshot = null
        hasRenderedOnce = false
        suppressRoomClosedUntil = 0L
    }

    /** 首屏：REST 快照 + 长连接。长连接握手时服务端也会立刻推一份全量快照。 */
    private fun bootstrap() {
        val token = sessionManager.getToken() ?: return

        bootstrapJob?.cancel()
        bootstrapJob = lifecycleScope.launch {
            when (val outcome = api.fetchState(token)) {
                is StateOutcome.Ok -> applySnapshot(outcome.snapshot)
                StateOutcome.Unauthorized -> forceReLogin("登录状态已失效，请重新登录")
                is StateOutcome.Failed -> {
                    if (!hasRenderedOnce) renderUnavailableState()
                }
            }
        }

        refreshProfile()
        connectRealtimeIfPossible()
    }

    /** JWT 里的昵称是签发时的快照，改过名字就是脏的，所以每次进前台都拉一次最新档案 */
    private fun refreshProfile() {
        val token = sessionManager.getToken() ?: return

        lifecycleScope.launch {
            when (val outcome = api.fetchProfile(token)) {
                is ProfileOutcome.Ok -> {
                    if (outcome.qq.isNotEmpty()) sessionManager.saveQq(outcome.qq)
                    if (outcome.username.isNotEmpty()) sessionManager.saveUsername(outcome.username)
                    if (outcome.avatarUrl.isNotEmpty()) sessionManager.saveAvatarUri(outcome.avatarUrl)
                    updateUserUi()
                    refreshMembersTab()
                }
                ProfileOutcome.Unauthorized -> forceReLogin("登录状态已失效，请重新登录")
                is ProfileOutcome.Failed -> Unit
            }
        }
    }

    private fun connectRealtimeIfPossible() {
        val token = sessionManager.getToken() ?: return
        realtime.connect(token)
    }

    // ==========================================================
    // 状态渲染（唯一入口）
    // ==========================================================

    private fun applySnapshot(incoming: RoomSnapshot) {
        // 乱序到达的旧快照直接丢弃，绝不让它把新状态覆盖回去
        if (incoming.version < lastVersion) return
        lastVersion = incoming.version

        snapshot = incoming
        hasRenderedOnce = true

        // 实时在线集合，花名册的角标全靠它
        onlineQq = incoming.members.map { it.qq }.filter { it.isNotEmpty() }.toSet()
        hostQq = incoming.room?.publisherQq
            ?: incoming.members.firstOrNull { it.isHosting }?.qq

        renderFromSnapshot()
        refreshMembersTab()
    }

    private fun renderFromSnapshot() {
        val snap = snapshot ?: return
        if (isPublishing) return

        // 顺序很重要：先按服务端的说法对齐房主身份，再决定进度条用哪个时基，最后才渲染。
        // 反过来的话，「我掉线、别人接管」的那一帧会拿服务端时间戳去套本机时基，
        // 进度条会整体偏移，要等到下一帧才被纠正。
        reconcileHosting(snap.room)
        if (!isHosting) {
            progressTracker.updateServerTime(snap.serverTime)
        }

        renderRoom(snap.room)
    }

    /** 首屏加载动画只在「真的还没有任何数据」时出现，不再每次 onResume 都闪一下 */
    private fun renderInitialState() {
        if (!sessionManager.isLoggedIn()) {
            renderLoggedOutState()
            return
        }
        if (hasRenderedOnce) return

        switchAnimation(AnimationTemplates.ANIM_SPINNER)
        tvHostMessage.isVisible = false
        tvHostMessage.text = ""
        layoutAudioPlayerCard.isVisible = false
        ivHostAvatar.isVisible = false
        btnJoin.isVisible = false
        layoutHostSection.isVisible = false
    }

    private fun renderLoggedOutState() {
        switchAnimation(AnimationTemplates.ANIM_HEX)
        tvHostMessage.isVisible = true
        tvHostMessage.text = "登录后即可加入群友的房间"

        ivHostAvatar.isVisible = false
        btnJoin.isVisible = false
        layoutHostSection.isVisible = false
        layoutAudioPlayerCard.isVisible = false

        progressTracker.reset()
        pbPlayerProgress.progress = 0

        renderMembersLoggedOut()
    }

    private fun renderUnavailableState() {
        switchAnimation(AnimationTemplates.ANIM_HEX)
        tvHostMessage.isVisible = true
        tvHostMessage.text = "暂时连不上服务器"

        ivHostAvatar.isVisible = false
        btnJoin.isVisible = false
        layoutHostSection.isVisible = false
    }

    private fun renderRoom(room: ActiveRoom?) {
        switchAnimation(AnimationTemplates.ANIM_HEX)
        tvHostMessage.isVisible = true

        if (room == null) {
            tvHostMessage.text = "空闲"
            tvPlayerSongTitle.text = ""
            tvPlayerArtist.text = ""
            ivPlayerAlbumCover.setImageResource(R.drawable.bg_avatar_gray)

            layoutAudioPlayerCard.isVisible = false
            ivHostAvatar.isVisible = false
            btnJoin.isVisible = false
            layoutHostSection.isVisible = true

            progressTracker.reset()
            pbPlayerProgress.progress = 0
            return
        }

        val meHosting = isMeHosting(room)
        tvHostMessage.text = if (meHosting) "你正在放歌" else "${room.inviter ?: "群友"} 正在放歌"

        renderPlayerCard(room, meHosting)

        ivHostAvatar.isVisible = true
        loadCircleImage(ivHostAvatar, room.hostAvatarUrl)

        btnJoin.isVisible = !meHosting
        layoutHostSection.isVisible = false
    }

    private fun renderPlayerCard(room: ActiveRoom, meHosting: Boolean) {
        // 房主自己就是数据源，画面直接吃 Neri 回调。
        // 否则服务器回声会把刚跳到的新进度又拽回上一个锚点，看起来就是「进度条慢半拍」。
        if (meHosting && isHosting && liveSongTitle != null) return

        val song = room.currentSong
        if (song.isNullOrBlank()) {
            layoutAudioPlayerCard.isVisible = false
            progressTracker.reset()
            pbPlayerProgress.progress = 0
            return
        }

        layoutAudioPlayerCard.isVisible = true

        val parts = song.split(" - ")
        tvPlayerSongTitle.text = if (parts.size > 1) parts.dropLast(1).joinToString(" - ") else song
        tvPlayerArtist.text = if (parts.size > 1) parts.last() else "NeriPlayer"
        tvPlayerSongTitle.isSelected = true

        loadCircleImage(ivPlayerAlbumCover, room.currentCover)

        progressTracker.updateMetrics(
            durationMs = room.durationMs,
            basePositionMs = room.basePositionMs,
            baseTimestampMs = room.baseTimestampMs,
            playbackRate = room.playbackRate,
            isPlaying = room.isPlaying
        )
    }

    private fun currentRoom(): ActiveRoom? = snapshot?.room

    private fun isMeHosting(room: ActiveRoom?): Boolean {
        if (room == null || !sessionManager.isLoggedIn()) return false

        val myQq = sessionManager.getQq()
        if (myQq.isNotEmpty() && !room.publisherQq.isNullOrEmpty()) {
            return myQq == room.publisherQq
        }

        // 老账号可能还没拿到 qq，退回昵称比对
        val myName = sessionManager.getUsername()
        return myName == room.publisher || myName == room.inviter
    }

    // ==========================================================
    // 成员列表
    // ==========================================================

    private fun refreshMembersTab() {
        if (!layoutMembersTabContent.isVisible) return

        if (!sessionManager.isLoggedIn()) {
            renderMembersLoggedOut()
            return
        }

        fetchRosterIfStale()
        renderMembers()
    }

    private fun renderMembersLoggedOut() {
        tvMemberCountBadge.isVisible = false
        layoutMembersContainer.removeAllViews()
        layoutMembersContainer.addView(buildMembersHint("登录后查看成员名单"))
    }

    private fun fetchRosterIfStale() {
        val token = sessionManager.getToken() ?: return

        val now = System.currentTimeMillis()
        if (now - lastRosterFetchAt < ROSTER_CACHE_MS) return
        if (rosterJob?.isActive == true) return

        lastRosterFetchAt = now
        rosterJob = lifecycleScope.launch {
            api.fetchRoster(token)
                .onSuccess { list ->
                    roster.clear()
                    roster.addAll(list)
                    rosterLoaded = true
                    rosterError = null
                    renderMembers()
                }
                .onFailure { error ->
                    // 拉不到就沿用上一次的名单，绝不塌缩成一份短列表 ——
                    // 那正是「一会儿一个人一会儿两个人」的老毛病。
                    Log.w(TAG, "花名册拉取失败，沿用上次结果: ${error.message}")
                    rosterError = error.message
                    // 失败不占用缓存窗口，下次进成员页立刻重试
                    lastRosterFetchAt = 0L
                    if (!rosterLoaded) renderMembers()
                }
        }
    }

    /**
     * 名单 = 全部注册成员（稳定，不随在线状态增删），
     * 在线 / 在放歌 / 我 都是挂在行上的角标。
     */
    private fun renderMembers() {
        if (!rosterLoaded) {
            if (rosterError != null) {
                // 一直转圈是最糟的失败方式：拉不到就明说，重进本页会自动重试
                tvMemberCountBadge.isVisible = false
                layoutMembersContainer.removeAllViews()
                layoutMembersContainer.addView(buildMembersHint("成员列表加载失败，重进本页可重试"))
            } else {
                showMembersLoading()
            }
            return
        }

        val members = displayRoster()
        val onlineCount = members.count { isOnlineNow(it.qq) }

        tvMemberCountBadge.isVisible = true
        tvMemberCountBadge.text = "${members.size} 位已认证 · $onlineCount 在线"
        layoutMembersContainer.removeAllViews()

        if (members.isEmpty()) {
            layoutMembersContainer.addView(buildMembersHint("暂无注册成员"))
            return
        }

        val myQq = sessionManager.getQq()
        val myName = sessionManager.getUsername()

        for (member in members) {
            val itemView = layoutInflater.inflate(R.layout.item_user_member, layoutMembersContainer, false)
            val ivAvatar = itemView.findViewById<ShapeableImageView>(R.id.ivMemberAvatar)
            val tvName = itemView.findViewById<TextView>(R.id.tvMemberName)
            val tvMeBadge = itemView.findViewById<TextView>(R.id.tvMeBadge)
            val tvOnlineBadge = itemView.findViewById<TextView>(R.id.tvOnlineBadge)
            val tvHostingBadge = itemView.findViewById<TextView>(R.id.tvHostingBadge)

            tvName.text = member.username

            val isMe = if (myQq.isNotEmpty() && member.qq.isNotEmpty()) {
                member.qq == myQq
            } else {
                member.username == myName
            }
            tvMeBadge.isVisible = isMe
            tvOnlineBadge.isVisible = isOnlineNow(member.qq)
            tvHostingBadge.isVisible = isHostingNow(member)

            loadCircleImage(ivAvatar, member.avatarUrl)
            layoutMembersContainer.addView(itemView)
        }
    }

    /** 花名册为准；万一自己刚注册还没落库，先把自己补在最前面（只增不减） */
    private fun displayRoster(): List<MemberInfo> {
        val myQq = sessionManager.getQq()
        if (myQq.isEmpty() || roster.any { it.qq == myQq }) return roster

        return buildList {
            add(
                MemberInfo(
                    qq = myQq,
                    username = sessionManager.getUsername(),
                    avatarUrl = sessionManager.getAvatarUri().orEmpty(),
                    isHosting = false,
                    isOnline = true
                )
            )
            addAll(roster)
        }
    }

    private fun isOnlineNow(qq: String): Boolean = qq.isNotEmpty() && onlineQq.contains(qq)

    private fun isHostingNow(member: MemberInfo): Boolean {
        val qq = hostQq
        return if (qq != null) member.qq == qq else member.isHosting
    }

    private fun showMembersLoading() {
        tvMemberCountBadge.isVisible = false
        layoutMembersContainer.removeAllViews()

        val loader = getOrCreateMembersLoader()
        (loader.parent as? ViewGroup)?.removeView(loader)
        loader.loadDataWithBaseURL(
            null,
            AnimationTemplates.getDotSpinnerHtml(),
            "text/html",
            "UTF-8",
            null
        )

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp2px(260)
        ).apply {
            gravity = Gravity.CENTER
            topMargin = dp2px(40)
        }
        layoutMembersContainer.addView(loader, params)
    }

    private fun buildMembersHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp2px(70), 0, dp2px(70))
        }
    }

    // ==========================================================
    // 房主：状态上报
    // ==========================================================

    private fun onHostPlaybackUpdate(
        songTitle: String?,
        artist: String?,
        coverUrl: String?,
        durationMs: Long,
        basePosMs: Long,
        playbackRate: Double,
        isPlaying: Boolean
    ) {
        // 校验阶段也要接收：join 响应里带的首帧状态必须缓存下来，
        // 否则开房成功后要等到下一次切歌才有画面。
        if (!isHosting && !isVerifyingSecret) return

        if (songTitle.isNullOrBlank()) {
            liveSongTitle = null
            liveArtist = null
            liveCoverUrl = null
            liveDurationMs = 0L
            liveBasePosMs = 0L
            liveAnchorAtMs = 0L
            liveIsPlaying = false
            livePlaybackRate = 1.0

            if (isHosting) {
                layoutAudioPlayerCard.isVisible = false
                progressTracker.reset()
                pbPlayerProgress.progress = 0
            }
            return
        }

        liveSongTitle = songTitle
        liveArtist = artist
        liveCoverUrl = coverUrl
        liveDurationMs = durationMs
        liveBasePosMs = basePosMs
        liveAnchorAtMs = System.currentTimeMillis()
        liveIsPlaying = isPlaying
        livePlaybackRate = playbackRate

        // 校验阶段只缓存，画面与后端上报都等开房成功之后
        if (!isHosting) return

        // 房主自己的画面直接吃 Neri 回调，零延迟
        layoutAudioPlayerCard.isVisible = true
        tvPlayerSongTitle.text = songTitle
        tvPlayerArtist.text = artist ?: "NeriPlayer"
        tvPlayerSongTitle.isSelected = true
        loadCircleImage(ivPlayerAlbumCover, coverUrl)

        progressTracker.useLocalClock()
        progressTracker.updateMetrics(
            durationMs = durationMs,
            basePositionMs = basePosMs,
            baseTimestampMs = liveAnchorAtMs,
            playbackRate = playbackRate,
            isPlaying = isPlaying
        )

        pushHostState(includePlayback = true)
    }

    /** 房主本地的实时播放位置：锚点 + 已经过去的时间，保证上报给服务器的位置单调递增 */
    private fun currentHostPositionMs(): Long {
        if (!liveIsPlaying) return liveBasePosMs
        val elapsed = System.currentTimeMillis() - liveAnchorAtMs
        val position = liveBasePosMs + elapsed
        return if (liveDurationMs > 0) position.coerceAtMost(liveDurationMs) else position
    }

    private fun pushHostState(includePlayback: Boolean) {
        val token = sessionManager.getToken() ?: return
        if (!isHosting) return

        val title = liveSongTitle
        val playback = if (includePlayback && !title.isNullOrEmpty()) {
            PlaybackPayload(
                currentSong = if (!liveArtist.isNullOrEmpty()) "$title - $liveArtist" else title,
                currentCover = liveCoverUrl,
                durationMs = liveDurationMs,
                basePositionMs = currentHostPositionMs(),
                isPlaying = liveIsPlaying
            )
        } else {
            null
        }

        lifecycleScope.launch {
            when (val outcome = api.hostPushState(token, playback)) {
                PushOutcome.Ok -> touchHostingRecord()
                PushOutcome.RoomLost -> {
                    // 服务端已经没有我的房间了，交给下一轮 reconcile 去重挂
                    Log.w(TAG, "服务端已无本房间，等待重新挂载")
                }
                PushOutcome.Unauthorized -> forceReLogin("登录状态已失效，请重新登录")
                is PushOutcome.Failed -> Unit
            }
        }
    }

    private fun startHostKeepalive() {
        if (hostKeepaliveJob?.isActive == true) return

        hostKeepaliveJob = lifecycleScope.launch {
            while (isActive && isHosting) {
                delay(HOST_KEEPALIVE_INTERVAL_MS)
                if (isHosting) pushHostState(includePlayback = true)
            }
        }
    }

    /**
     * 把本地房主身份和服务端状态对齐：
     *  - 服务端有房间、房主是我 → 需要时把房主身份和 Neri 连接接回来
     *  - 服务端有房间、房主是别人 → 放弃本地房主身份
     *  - 服务端没房间、本地还留着房主记录 → 重新挂上去
     *    （房主 App 被系统杀掉时收不到关闭通知，只能靠这条路径恢复）
     */
    private fun reconcileHosting(room: ActiveRoom?) {
        // isRevivingHost 是关键：App 重启后第一波会连着来好几条「无房间」快照
        // （bootstrap 的 REST 快照 + 长连接握手的广播 + 其他人的连接抖动）。
        // 没有这个在途标记，每一条都会白白扣掉一次重试额度，
        // 三次之后就误判成「房间已解散」，把正在恢复的房间拆掉。
        if (isPublishing || isRevivingHost) return
        val token = sessionManager.getToken() ?: return

        if (room != null) {
            if (isMeHosting(room)) {
                adoptHostingIfNeeded()
            } else if (isHosting) {
                Log.i(TAG, "房间已被他人接管，放弃本地房主身份")
                stopHosting(notifyServer = false)
            }
            return
        }

        // 服务端没有房间。房主记录只在「主动关房」或「确认解散」时才会被清掉，
        // 所以它还在就说明上次多半是被系统杀掉的，值得重挂。
        val persisted = currentRoomInfo ?: restoreHostingRoom()
        if (persisted == null) {
            if (isHosting) stopHosting(notifyServer = false)
            return
        }

        if (hostReviveCount >= MAX_HOST_REVIVE) {
            stopHosting(notifyServer = false)
            showTip("房间已解散")
            return
        }

        hostReviveCount++
        isRevivingHost = true
        Log.i(TAG, "服务端无房间，重新挂载（第 $hostReviveCount 次）")

        lifecycleScope.launch {
            try {
                when (val outcome = api.hostStart(token, persisted)) {
                    is StartOutcome.Ok -> {
                        currentRoomInfo = persisted
                        persistHostingRoom(persisted)
                        adoptHostingIfNeeded()
                    }
                    is StartOutcome.Conflict -> {
                        stopHosting(notifyServer = false)
                        showTip(outcome.message)
                    }
                    StartOutcome.Unauthorized -> forceReLogin("登录状态已失效，请重新登录")
                    is StartOutcome.Failed -> {
                        if (hostReviveCount >= MAX_HOST_REVIVE) {
                            stopHosting(notifyServer = false)
                            showTip("房间已解散")
                        }
                    }
                }
            } finally {
                isRevivingHost = false
            }
        }
    }

    /** 服务端确认我是房主，但本地还没在放歌 —— 把 Neri 长连接和保活循环接回来 */
    private fun adoptHostingIfNeeded() {
        if (isHosting) return

        val persisted = currentRoomInfo ?: restoreHostingRoom() ?: return
        Log.i(TAG, "接管回房主身份 roomId=${persisted.roomId}")

        isHosting = true
        currentRoomInfo = persisted
        startNeriWatcher(persisted)
        startHostKeepalive()
    }

    private fun handleServerRoomClosed(reason: String, version: Long) {
        // 采纳关房的版本号：否则一条迟到的 REST 快照会带着相同的版本号
        // 通过排序检查，把刚关掉的房间又画回来。
        if (version > lastVersion) lastVersion = version

        val wasHosting = isHosting
        stopHosting(notifyServer = false)

        snapshot = snapshot?.copy(room = null)
        // 注意：不清理 onlineQq —— 关房不影响谁还连着，成员仍然是在线的
        hostQq = null

        if (!isPublishing) renderRoom(null)
        refreshMembersTab()

        if (System.currentTimeMillis() < suppressRoomClosedUntil) return

        when {
            wasHosting && reason == "timeout" -> showTip("房间长时间无响应，已自动关闭")
            // 后端主动探测到播放器里的房间已经没了
            wasHosting && reason == "room_gone" -> showTip("播放器里的房间已结束，已自动关房")
            wasHosting -> Unit
            else -> showTip("房主已结束放歌")
        }
    }

    // ==========================================================
    // 房主：开播 / 关播
    // ==========================================================

    /**
     * 开房第一步：先去播放器服务器验证口令。
     *
     * 顺序很关键。以前是「直接让后端开房，再慢慢去连播放器」，于是粘一个过期的
     * 邀请链接也会在后端开出一个指向**已不存在的 Neri 房间**的幽灵房 ——
     * 成员看得到、房主自己又满世界找不到关掉它的入口。
     *
     * 现在只有 join 成功（也就是口令真的有效、房间真的还在）才会去调后端开房。
     * 校验用的就是房主本来就该建立的那条 Neri 长连接，不会多出一个机器人。
     */
    private fun verifyAndStartHosting(info: RoomInfo) {
        val roomId = info.roomId?.trim()
        val secret = info.secret?.trim()

        if (roomId.isNullOrEmpty() || !roomId.matches(Regex("^[a-zA-Z0-9]{6}$")) || secret.isNullOrEmpty()) {
            showTip("口令格式错误：未解析到合法的6位房间号或密钥！")
            return
        }
        if (isPublishing || isVerifyingSecret) return

        if (sessionManager.getToken().isNullOrEmpty()) {
            showTip("请先登录")
            return
        }

        isVerifyingSecret = true
        pendingRoomInfo = info
        setPublishControlsEnabled(false, "校验中...")
        showTip("正在校验邀请口令...")

        startNeriWatcher(info)

        verifyJob?.cancel()
        verifyJob = lifecycleScope.launch {
            delay(SECRET_VERIFY_TIMEOUT_MS)
            if (isVerifyingSecret) {
                cancelSecretVerification()
                showTip("无法连接播放器服务器，请检查口令或网络")
            }
        }
    }

    /** Neri 确认 join 成功：口令有效，这才轮到后端开房 */
    private fun onNeriConnected() {
        if (!isVerifyingSecret) return

        val info = pendingRoomInfo
        val token = sessionManager.getToken()
        if (info == null || token.isNullOrEmpty()) {
            cancelSecretVerification()
            showTip("请先登录")
            return
        }

        verifyJob?.cancel()
        verifyJob = null
        isVerifyingSecret = false
        isPublishing = true
        setPublishControlsEnabled(false, "开启中...")

        lifecycleScope.launch {
            val outcome = api.hostStart(token, info)
            isPublishing = false
            setPublishControlsEnabled(true, "开启")

            when (outcome) {
                is StartOutcome.Ok -> onHostingStarted(info, outcome.room)

                // 后面这几种都是「没开成」，Neri 那条连接必须收掉，否则会一直挂着
                is StartOutcome.Conflict -> {
                    abandonPendingRoom()
                    showTip(outcome.message)
                    renderFromSnapshot()
                }
                StartOutcome.Unauthorized -> {
                    abandonPendingRoom()
                    forceReLogin("登录状态已失效，请重新登录")
                }
                is StartOutcome.Failed -> {
                    abandonPendingRoom()
                    showTip(outcome.message ?: "开启失败")
                    renderFromSnapshot()
                }
            }
        }
    }

    /** 口令校验失败或超时：收掉 Neri 连接、恢复按钮，绝不碰后端 */
    private fun cancelSecretVerification() {
        verifyJob?.cancel()
        verifyJob = null
        isVerifyingSecret = false
        pendingRoomInfo = null
        neriWatcher.stopWatching()
        setPublishControlsEnabled(true, "开启")
    }

    /** 后端没接受这次开房，把已经建立的 Neri 连接收干净 */
    private fun abandonPendingRoom() {
        pendingRoomInfo = null
        neriWatcher.stopWatching()
    }

    private fun onHostingStarted(info: RoomInfo, room: ActiveRoom?) {
        hostReviveCount = 0
        isHosting = true
        currentRoomInfo = info
        pendingRoomInfo = null

        persistHostingRoom(info)
        etInviteCode.setText("")
        setPublishControlsEnabled(true, "开启")

        // 幂等：校验阶段就已经连上了，这里不会重复建连
        startNeriWatcher(info)
        startHostKeepalive()

        // 校验阶段缓存下来的首帧现在补上，否则要等到下一次切歌才有画面
        replayCachedPlayback()

        room?.let { renderRoom(it) }
        showTip("房间上线成功！")
    }

    /** 把校验阶段从 join 响应里拿到的首帧播放状态补画出来并上报给后端 */
    private fun replayCachedPlayback() {
        val title = liveSongTitle ?: return

        layoutAudioPlayerCard.isVisible = true
        tvPlayerSongTitle.text = title
        tvPlayerArtist.text = liveArtist ?: "NeriPlayer"
        tvPlayerSongTitle.isSelected = true
        loadCircleImage(ivPlayerAlbumCover, liveCoverUrl)

        if (liveDurationMs > 0) {
            progressTracker.useLocalClock()
            progressTracker.updateMetrics(
                durationMs = liveDurationMs,
                basePositionMs = liveBasePosMs,
                baseTimestampMs = liveAnchorAtMs,
                playbackRate = livePlaybackRate,
                isPlaying = liveIsPlaying
            )
        }

        pushHostState(includePlayback = true)
    }

    private fun stopHosting(notifyServer: Boolean) {
        val wasHosting = isHosting

        isHosting = false
        isVerifyingSecret = false
        verifyJob?.cancel()
        verifyJob = null
        pendingRoomInfo = null
        hostReviveCount = 0
        hostKeepaliveJob?.cancel()
        hostKeepaliveJob = null
        neriWatcher.stopWatching()
        currentRoomInfo = null
        sessionManager.clearHostingRoom()

        liveSongTitle = null
        liveArtist = null
        liveCoverUrl = null
        liveDurationMs = 0L
        liveBasePosMs = 0L
        liveAnchorAtMs = 0L
        liveIsPlaying = false
        livePlaybackRate = 1.0

        if (wasHosting && notifyServer) {
            suppressRoomClosedUntil = System.currentTimeMillis() + ROOM_CLOSED_SUPPRESS_MS
            val token = sessionManager.getToken()
            if (token != null) {
                lifecycleScope.launch { api.hostStop(token) }
            }
        }
    }

    private fun startNeriWatcher(info: RoomInfo) {
        val roomId = info.roomId ?: return
        val secret = info.secret ?: return
        if (roomId.isEmpty() || secret.isEmpty()) return

        neriWatcher.startWatching(
            info.serverUrl ?: ApiConfig.DEFAULT_NERI_SERVER,
            roomId,
            secret,
            sessionManager.getUsername()
        )
    }

    private fun setPublishControlsEnabled(enabled: Boolean, text: String) {
        btnPublishRoom.isEnabled = enabled
        btnPublishRoom.text = text
        btnPasteInvite.isEnabled = enabled
        etInviteCode.isEnabled = enabled
    }

    // ==========================================================
    // 房主身份持久化
    // ==========================================================

    private fun persistHostingRoom(info: RoomInfo) {
        val json = JSONObject().apply {
            put("ownerQq", sessionManager.getQq())
            put("owner", sessionManager.getUsername())
            put("roomId", info.roomId ?: "")
            put("secret", info.secret ?: "")
            put("deepLink", info.rawUri)
            put("serverUrl", info.serverUrl ?: ApiConfig.DEFAULT_NERI_SERVER)
            put("inviter", info.inviter ?: "")
            put("savedAt", System.currentTimeMillis())
        }
        lastRecordRefreshAt = System.currentTimeMillis()
        sessionManager.saveHostingRoom(json.toString())
    }

    /** 房主还在正常放歌时定期把记录的时间戳续上，否则它会在 30 分钟后过期 */
    private fun touchHostingRecord() {
        val now = System.currentTimeMillis()
        if (now - lastRecordRefreshAt < RECORD_REFRESH_INTERVAL_MS) return
        currentRoomInfo?.let { persistHostingRoom(it) }
    }

    private fun restoreHostingRoom(): RoomInfo? {
        val raw = sessionManager.getHostingRoom() ?: return null

        return try {
            val json = JSONObject(raw)

            // 换了账号登录，旧的房主身份必须作废。
            // 用 qq 而不是昵称比对 —— 昵称是可以随时改的，改了不该把房间丢掉。
            val ownerQq = json.optString("ownerQq", "")
            val myQq = sessionManager.getQq()
            if (ownerQq.isNotEmpty() && myQq.isNotEmpty() && ownerQq != myQq) {
                sessionManager.clearHostingRoom()
                return null
            }

            // 过期记录直接作废，别在第二天打开 App 时凭空拉起一个僵尸房间
            val savedAt = json.optLong("savedAt", 0L)
            if (savedAt > 0 && System.currentTimeMillis() - savedAt > HOST_RECORD_MAX_AGE_MS) {
                Log.i(TAG, "房主记录已过期，作废")
                sessionManager.clearHostingRoom()
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

    // ==========================================================
    // 视图工具
    // ==========================================================

    private fun showTip(message: String) = tipManager.showTip(message)

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadCircleImage(view: ShapeableImageView, url: String?) {
        if (url.isNullOrEmpty()) {
            view.setImageResource(R.drawable.bg_avatar_gray)
            return
        }
        view.load(url) {
            crossfade(true)
            placeholder(R.drawable.bg_avatar_gray)
            error(R.drawable.bg_avatar_gray)
            transformations(CircleCropTransformation())
        }
    }

    private fun switchTab(isMusicTab: Boolean) {
        layoutMusicTabContent.isVisible = isMusicTab
        layoutMembersTabContent.isVisible = !isMusicTab
        if (isMusicTab) {
            tvTabMusic.setTextColor(0xFF0F172A.toInt())
            tvTabMembers.setTextColor(0xFF94A3B8.toInt())
        } else {
            tvTabMusic.setTextColor(0xFF94A3B8.toInt())
            tvTabMembers.setTextColor(0xFF0F172A.toInt())
        }
    }

    private fun switchAnimation(animType: Int) {
        if (currentAnimType == animType) return
        currentAnimType = animType

        val htmlContent = when (animType) {
            AnimationTemplates.ANIM_SPINNER -> AnimationTemplates.getDotSpinnerHtml()
            AnimationTemplates.ANIM_HEX -> AnimationTemplates.getHexLoaderHtml()
            else -> ""
        }
        webHexLoader.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }

    private var currentAnimType = AnimationTemplates.ANIM_NONE

    private fun startActivitySafely(className: String) {
        try {
            val intent = Intent(this, Class.forName(className))
            startActivity(intent)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "目标 Activity 尚未创建: $className", e)
            showTip("模块正在开发中")
        }
    }

    private fun verifyAndJoinRoom(link: String) {
        val launched = NeriDeepLinkHelper.launchPlayer(this@MainActivity, link)
        if (!launched) {
            showTip("未找到 NeriPlayer，请确认已安装！")
        }
    }
    private fun updateUserUi() {
        if (sessionManager.isLoggedIn()) {
            tvCurrentUserName.text = sessionManager.getUsername()
            btnLogout.isVisible = false
            loadCircleImage(ivUserAvatar, sessionManager.getAvatarUri())
        } else {
            tvCurrentUserName.text = "未登录"
            btnLogout.isVisible = true
            btnLogout.text = "登录"
            ivUserAvatar.setImageResource(R.drawable.bg_avatar_gray)
        }
    }
}
