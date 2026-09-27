package com.example.social_music

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.social_music.manager.HostSession
import com.example.social_music.manager.LivePlayback
import com.example.social_music.manager.PlaybackProgressTracker
import com.example.social_music.model.ActiveRoom
import com.example.social_music.model.MemberInfo
import com.example.social_music.model.RoomInfo
import com.example.social_music.model.RoomSnapshot
import com.example.social_music.net.ProfileOutcome
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
import kotlinx.coroutines.launch

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
class MainActivity : AppCompatActivity(), HostSession.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_HOST_REVIVE = 3

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

    /**
     * 实时通道当前是断的。
     *
     * 断线时画面刻意保持不动（清空才是「成员一会儿一个一会儿两个」那类抖动的来源），
     * 但得让用户知道看到的东西可能已经过时，不然一首早就切掉的歌会一直显示成正在播放。
     */
    private var realtimeDown = false

    // ---------- 房主本地状态 ----------
    // Neri 长连接、保活循环、状态上报和房主记录都已经搬进 HostSession ——
    // 它们必须活过 Activity 的销毁，否则用户划掉 App 房间就成僵尸了。
    // 这里只留「界面自己的」流程状态。
    private var isPublishing = false
    private var isRevivingHost = false
    private var hostReviveCount = 0

    /**
     * 服务端明确说过「这个用户已经没有房间了」。
     *
     * 这是重挂的**唯一**依据。不能拿「快照里 room == null」当依据 ——
     * 开房时那个 pending 占位房间在被点亮之前，快照看起来一模一样，
     * 误判会导致刚开好的房间被当成「已解散」拆掉，
     * 表现就是「有时候挺快，有时候过一会儿就不更新了」。
     */
    private var hostRoomLost = false

    /** 主动关房后的静默截止时间 */
    private var suppressRoomClosedUntil = 0L

    // ---------- 开房流程（并行：校验口令 + 后端占位同时发出）----------
    // 顺序仍然是「先确认房间真实存在，再让后端开房」，只是不再串行等待：
    // 后端那一路先挂一个对成员不可见的 pending 房间，口令校验通过后再点亮。
    // 于是开房耗时 = max(两次往返) 而不是相加，省掉一整次跨境往返。
    private var isVerifyingSecret = false
    private var pendingRoomInfo: RoomInfo? = null
    private var verifyJob: Job? = null

    /** 后端那次开房请求 */
    private var startJob: Job? = null

    /** 后端开房结果；null 表示请求还在途 */
    private var startOutcome: StartOutcome? = null

    /** 请求是否已经落地。它是「撤销占位房间」由哪一方负责的判据，见 cancelSecretVerification */
    private var startSettled = true

    /**
     * 开房轮次。每次发起自增，用来作废上一轮还在途的请求 ——
     * 否则一次迟到的结果会写进 startOutcome，被下一轮当成自己的结果读走
     * （比如上一轮的「别人正在放歌」把新一次开房直接掐掉）。
     */
    private var startAttempt = 0

    // ---------- 任务 ----------
    private var bootstrapJob: Job? = null

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

        // HostSession 是进程级的，要在任何房主流程之前初始化好
        HostSession.init(applicationContext)

        initViews()
        initWebViewSettings()
        initProgressTracker()
        initRealtime()
        initNotificationPermission()
        setupListeners()

        renderInitialState()
        if (wasLoggedIn) bootstrap()
    }

    override fun onStart() {
        super.onStart()

        // 重新附着到可能仍在跑的房主会话（Activity 重建、或从后台回来）。
        // HostSession 是进程级的，所以这里是同步的，不存在「还没绑定好」的空窗。
        // 必须重画一次当前状态，否则界面会一直空到下一次切歌为止。
        HostSession.setListener(this)
        if (HostSession.isHosting) {
            // 只在真的在放歌时才画：校验阶段的缓存帧还不能代表「房间已上线」
            HostSession.livePlayback?.let { renderLivePlayback(it) }
        }
        if (HostSession.consumeAuthExpired()) {
            forceReLogin("登录状态已失效，请重新登录")
        }

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

        // 先摘订阅者再拆连接：保证拆的过程中不会有回调打到已经停下来的界面。
        // 注意摘的只是「界面订阅」，房主会话本身照跑 —— 那是 HostSession 和
        // MusicSyncService 的事，切后台正是它们要顶住的场景。
        HostSession.setListener(null)

        // 实时通道只服务于前台画面，切后台断开省电。
        // 房主的状态上报走 HTTP，不受影响；而且服务端会强制把房主留在成员列表里。
        realtime.disconnect()

        progressTracker.pause()

        // 校验到一半切后台：直接放弃本次校验，别留个半吊子状态
        if (isVerifyingSecret) {
            cancelSecretVerification()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 刻意不在这里关房、不清房主记录、也不停 HostSession：
        // 转屏或 Activity 回收不该把正在放歌的房间打掉 —— 开播期间有前台服务顶着，
        // 真正的兜底是用户主动关播、服务端房间消失、或 90 秒心跳超时。
        HostSession.setListener(null)
        bootstrapJob?.cancel()
        verifyJob?.cancel()
        // 刻意不取消 startJob：onStop 已经用 abandonStartAttempt() 把它作废了，
        // 而它必须能跑完才能自己撤销那个可能已经挂出去的占位房间。
        // 直接 cancel 会让清理逻辑整段跳过，房间要等 30 秒 pending TTL 才回收。
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

            override fun onConnected() {
                // 通道恢复：把「连接不稳定」的标记撤掉
                if (realtimeDown) {
                    realtimeDown = false
                    renderFromSnapshot()
                }
            }

            override fun onDisconnected(reason: String?) {
                // 断线只影响新鲜度，绝不清空画面 —— 重连后会收到全量快照自动收敛。
                // 清空才是「成员一会儿一个一会儿两个」那类抖动的来源。
                if (reason == "登录状态已失效") {
                    forceReLogin("登录状态已失效，请重新登录")
                    return
                }
                // 画面保持不动，但必须让用户知道「现在看到的可能已经过时了」，
                // 而不是让一首已经切掉的歌继续显示成正在播放。
                realtimeDown = true
                renderFromSnapshot()
            }
        })
    }

    /**
     * API 33+ 的通知权限。
     *
     * 只在开播时才有必要问 —— 关掉也无所谓：前台服务照常运行，
     * 只是通知栏里看不到那条常驻通知。所以绝不能拿它去挡开播流程。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showTip("未授予通知权限，后台同步仍会运行，但看不到状态通知")
            }
        }

    private fun initNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ==========================================================
    // HostSession.Listener —— 房主会话事件（全部在主线程回调）
    // ==========================================================

    /** 房主端零延迟画面：直接吃 Neri 回调，不等服务端回声 */
    override fun onLivePlayback(playback: LivePlayback) {
        if (!HostSession.isHosting) return
        renderLivePlayback(playback)
    }

    override fun onLiveCleared() {
        if (!HostSession.isHosting) return
        layoutAudioPlayerCard.isVisible = false
        progressTracker.reset()
        pbPlayerProgress.progress = 0
    }

    /** 口令校验通过 —— 这才轮到后端开房（后端那一路其实早就并行发出去了） */
    override fun onVerifyConnected() {
        if (!isVerifyingSecret) return
        awaitStartOutcomeThenCommit()
    }

    override fun onVerifyRoomGone() {
        if (!isVerifyingSecret) return
        // 校验阶段就发现房间没了 = 口令已失效，绝不能再去后端开房
        cancelSecretVerification()
        showTip("邀请口令已失效，房间已不存在")
    }

    override fun onHostingStarted() {
        hostReviveCount = 0
        hostRoomLost = false
        setPublishControlsEnabled(true, "开启")
        showTip("房间上线成功！")
    }

    override fun onRoomLost() {
        // 服务端确实没有我的房间了，这才允许走重挂
        hostRoomLost = true
    }

    override fun onSessionStopped(reason: HostSession.StopReason) {
        resetHostUi()

        when (reason) {
            HostSession.StopReason.TIMEOUT ->
                showTip("后台同步已达系统时限，房间已自动关闭")
            HostSession.StopReason.AUTH_EXPIRED ->
                forceReLogin("登录状态已失效，请重新登录")
            HostSession.StopReason.ROOM_CLOSED -> Unit   // 由 Neri 那条路径自己提示
            HostSession.StopReason.SERVICE_GONE -> Unit
            HostSession.StopReason.USER -> Unit
        }
    }

    override fun onForegroundServiceUnavailable() {
        showTip("系统未允许后台服务，切到后台可能掉线")
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

        // 一解析出合法口令就预热播放器连接。
        // 用户粘完口令到真正点「开启」通常隔着一两秒，正好把那笔握手成本（实测最坏 5.7 秒）
        // 提前付掉。HostSession 里做了幂等，同一个地址不会重复预热。
        etInviteCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val info = NeriDeepLinkHelper.parseInvitation(s?.toString().orEmpty()) ?: return
                if (info.roomId.isNullOrEmpty()) return
                HostSession.preconnectNeri(info.serverUrl)
            }
        })

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

        // 开房流程的每一次在途请求也要收掉，否则换了身份之后
        // 一个还在飞的 hostStart 会把房间挂到新账号头上
        verifyJob?.cancel()
        verifyJob = null
        startJob = null
        abandonStartAttempt()
        pendingRoomInfo = null
        isVerifyingSecret = false
        isPublishing = false
        isRevivingHost = false
        hostReviveCount = 0
        hostRoomLost = false

        roster.clear()
        rosterLoaded = false
        rosterError = null
        lastRosterFetchAt = 0L

        onlineQq = emptySet()
        hostQq = null
        realtimeDown = false
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
        // 房主端的锚点时间戳来自本机 Neri 回调，是本机时基，不能再叠加服务器偏移
        if (!HostSession.isHosting) {
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
        val baseMessage = if (meHosting) "你正在放歌" else "${room.inviter ?: "群友"} 正在放歌"
        // 掉线时画面照旧，但明确标出来 —— 用户至少知道该等一下，而不是以为切歌坏了
        tvHostMessage.text = if (realtimeDown) "$baseMessage · 连接不稳定" else baseMessage

        renderPlayerCard(room, meHosting)

        ivHostAvatar.isVisible = true
        loadCircleImage(ivHostAvatar, room.hostAvatarUrl)

        btnJoin.isVisible = !meHosting
        layoutHostSection.isVisible = false
    }

    /**
     * 房主自己的画面：直接吃 Neri 回调，零延迟。
     *
     * 之所以不走服务端快照，是因为跨境的服务器回声会把刚跳到的新进度又拽回上一个锚点，
     * 看起来就是「进度条慢半拍」。
     */
    private fun renderLivePlayback(playback: LivePlayback) {
        layoutAudioPlayerCard.isVisible = true
        tvPlayerSongTitle.text = playback.songTitle
        tvPlayerArtist.text = playback.artist ?: "NeriPlayer"
        tvPlayerSongTitle.isSelected = true
        loadCircleImage(ivPlayerAlbumCover, playback.coverUrl)

        progressTracker.useLocalClock()
        progressTracker.updateMetrics(
            durationMs = playback.durationMs,
            basePositionMs = playback.basePosMs,
            baseTimestampMs = playback.anchorAtMs,
            playbackRate = playback.playbackRate,
            isPlaying = playback.isPlaying
        )

        tvHostMessage.isVisible = true
        tvHostMessage.text = "你正在放歌"
        ivHostAvatar.isVisible = true
        btnJoin.isVisible = false
        layoutHostSection.isVisible = false
    }

    private fun renderPlayerCard(room: ActiveRoom, meHosting: Boolean) {
        // 房主自己就是数据源，画面直接吃 Neri 回调。
        // 否则服务器回声会把刚跳到的新进度又拽回上一个锚点，看起来就是「进度条慢半拍」。
        if (meHosting && HostSession.isHosting && HostSession.livePlayback != null) return

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
    // 房主：与服务端对齐
    // ==========================================================

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
        //
        // isVerifyingSecret 同样关键：并行开房期间后端挂的是 pending 房间，
        // 快照里 room 就是 null。不挡住的话，这里会拿着**上一次遗留的**房主记录
        // 去重新 hostStart，把用户正在开的那个房间顶掉。
        if (isPublishing || isVerifyingSecret || isRevivingHost) return
        val token = sessionManager.getToken() ?: return

        if (room != null) {
            if (isMeHosting(room)) {
                adoptHostingIfNeeded()
            } else if (HostSession.isHosting) {
                Log.i(TAG, "房间已被他人接管，放弃本地房主身份")
                stopHosting(notifyServer = false)
            }
            return
        }

        // 正在放歌，服务端快照却说没有房间 —— 别急着重挂。
        //
        // 开房时那个 pending 占位房间在确认之前，快照就是这样子的。把它当成
        // 「房间掉了」会一路扣掉重挂额度，三次之后直接判定「房间已解散」并把
        // 刚开好的会话拆掉。所以只有服务端明确说过没房间（RoomLost），
        // 或者本地根本没在放歌，才轮到下面这条重挂路径。
        if (HostSession.isHosting && !hostRoomLost) {
            Log.i(TAG, "快照暂无房间，但本地仍在放歌（pending 点亮窗口），不重挂")
            return
        }

        // 服务端没有房间。房主记录只在「主动关房」或「确认解散」时才会被清掉，
        // 所以它还在就说明上次多半是被系统杀掉的，值得重挂。
        val persisted = HostSession.currentRoom ?: HostSession.restorePersistedRoom()
        if (persisted == null) {
            if (HostSession.isHosting) stopHosting(notifyServer = false)
            return
        }

        if (hostReviveCount >= MAX_HOST_REVIVE) {
            stopHosting(notifyServer = false)
            showTip("房间已解散")
            return
        }

        // 走到这里说明服务端确实没有我的房间了（hostRoomLost），或者本地本来就没在放歌。
        //
        // 这里刻意**不**先把会话停掉。之前是 stop() 完再 commitHosting()，而 stop() 会
        // 清空缓存的播放状态，于是重挂出来的房间是没有歌的 —— 而 NeriPlayer 只在状态
        // 变化时才推，没有任何东西会把歌补回来，用户就一直看着「你正在放歌」却没有歌曲。
        // 改成重挂成功后直接 reattach，播放状态原样带过去。
        hostReviveCount++
        hostRoomLost = false
        isRevivingHost = true
        Log.i(TAG, "服务端无房间，重新挂载（第 $hostReviveCount 次）")

        lifecycleScope.launch {
            try {
                when (val outcome = api.hostStart(token, persisted)) {
                    // reattach 而不是 commitHosting：后者只在全新会话时用。
                    // 重挂要保留本地播放状态，否则房间回来是空的（见 reattach 的注释）。
                    is StartOutcome.Ok -> HostSession.reattach(persisted)
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

    /**
     * 服务端确认我是房主，但本地还没在放歌 —— 把会话接回来。
     *
     * 注意这条路必须走 [HostSession.commitHosting]：进程被系统杀掉后重新挂载时，
     * 前台服务也要跟着起来。只置个本地标志位的话，就变成「房间活着但没有前台服务顶着」，
     * 划掉 App 照样掉线 —— 那正是这次要修的问题。
     */
    private fun adoptHostingIfNeeded(info: RoomInfo? = null) {
        if (HostSession.isHosting) return

        val persisted = info ?: HostSession.currentRoom ?: HostSession.restorePersistedRoom() ?: return
        Log.i(TAG, "接管回房主身份 roomId=${persisted.roomId}")
        HostSession.commitHosting(persisted)
    }

    private fun handleServerRoomClosed(reason: String, version: Long) {
        // 采纳关房的版本号：否则一条迟到的 REST 快照会带着相同的版本号
        // 通过排序检查，把刚关掉的房间又画回来。
        if (version > lastVersion) lastVersion = version

        val wasHosting = HostSession.isHosting
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
            // 并行开房时的占位房间一直没等到确认，多半是客户端半路走了
            reason == "pending_timeout" -> Unit
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
        // HostSession.isActive 也要挡：Activity 这一侧的标志位在重建后可能都归零，
        // 但会话其实还在跑。漏掉它就会对同一个房间再发一次 hostStart。
        if (isPublishing || isVerifyingSecret || HostSession.isActive) return

        val token = sessionManager.getToken()
        if (token.isNullOrEmpty()) {
            showTip("请先登录")
            return
        }

        isVerifyingSecret = true
        pendingRoomInfo = info
        startOutcome = null
        startSettled = false
        setPublishControlsEnabled(false, "校验中...")
        showTip("正在校验邀请口令...")

        // 两路并行发出，开房耗时 = max(两者) 而不是相加 —— 跨境链路上省掉一整次往返：
        //   ① 连播放器 join，确认口令有效、房间真实存在；
        //   ② 后端先挂一个 pending 房间，它对成员端完全不可见，等 ① 通过后再点亮。
        // 「先验证再开房」的顺序没有被破坏：① 成功之前，成员端什么都看不到。
        HostSession.beginVerification(info)
        val attempt = ++startAttempt
        startJob = lifecycleScope.launch {
            val outcome = api.hostStart(token, info, pending = true)

            if (attempt != startAttempt) {
                // 这一轮已经作废（被取消 / 用户重新开了一次 / 已登出）。
                // 房间可能已经建出来了，就地撤掉 —— 放在这里做是刻意的：
                // 此刻 hostStart 一定已经返回，撤销不可能抢在它前面到达，
                // 也就不会出现「撤销落空、占位房间残留」。
                if (outcome is StartOutcome.Ok) {
                    Log.i(TAG, "本轮开房已作废，撤销占位房间")
                    api.hostStop(token)
                }
                return@launch
            }

            startSettled = true
            startOutcome = outcome

            // 后端明确拒绝（比如别人正在放歌），不必再干等口令校验
            if (outcome !is StartOutcome.Ok && isVerifyingSecret) {
                cancelSecretVerification()
                when (outcome) {
                    is StartOutcome.Conflict -> showTip(outcome.message)
                    StartOutcome.Unauthorized -> forceReLogin("登录状态已失效，请重新登录")
                    is StartOutcome.Failed -> showTip(outcome.message ?: "开启失败")
                    else -> Unit
                }
                renderFromSnapshot()
            }
        }

        verifyJob?.cancel()
        verifyJob = lifecycleScope.launch {
            delay(SECRET_VERIFY_TIMEOUT_MS)
            if (isVerifyingSecret) {
                cancelSecretVerification()
                showTip("无法连接播放器服务器，请检查口令或网络")
            }
        }
    }

    /**
     * 口令校验通过（[HostSession.Listener.onVerifyConnected] 触发）：
     * 房间确实存在，现在把后端那一路的结果接上。
     *
     * 后端请求是并行发出的，可能已经回来了，也可能还在飞 —— 这里等它落地。
     * 等的是「较慢的那一路」，所以总耗时仍然是一次往返，而不是两次相加。
     */
    private fun awaitStartOutcomeThenCommit() {
        // Neri 在弱网下可能重复报 join 成功，别把收尾流程跑两遍
        if (isPublishing || HostSession.isHosting) return

        val info = pendingRoomInfo
        if (info == null || sessionManager.getToken().isNullOrEmpty()) {
            cancelSecretVerification()
            showTip("请先登录")
            return
        }

        verifyJob?.cancel()
        verifyJob = null
        isPublishing = true
        setPublishControlsEnabled(false, "开启中...")

        lifecycleScope.launch {
            startJob?.join()

            val outcome = startOutcome
            startOutcome = null
            isPublishing = false
            setPublishControlsEnabled(true, "开启")

            // 等结果的过程中被取消了（切后台 / 超时），别再往下走
            if (!isVerifyingSecret || HostSession.isHosting) return@launch
            isVerifyingSecret = false

            when (outcome) {
                is StartOutcome.Ok -> onHostingStarted(info)

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
                null -> {
                    abandonPendingRoom()
                    showTip("开启失败，请重试")
                    renderFromSnapshot()
                }
            }
        }
    }

    /**
     * 口令校验失败或超时：收掉 Neri 连接、恢复按钮。
     *
     * 后端那一路是并行发出的，可能已经挂出了一个 pending 房间，得撤销。撤销分两半，
     * 靠 [startSettled] 判别、恰好有一方命中：
     *  - 请求已落地 → 这里直接 hostStop（此刻房间一定存在，不存在抢跑的竞态）；
     *  - 请求还在途 → 交给 startJob 落地时自己清理（见上面的轮次判断）。
     * 万一进程在中途被杀，服务端还有 30 秒的 pending TTL 兜底 —— 反正它对成员不可见。
     */
    private fun cancelSecretVerification() {
        verifyJob?.cancel()
        verifyJob = null
        isVerifyingSecret = false
        isPublishing = false
        pendingRoomInfo = null
        HostSession.cancelVerification()
        setPublishControlsEnabled(true, "开启")
        abandonStartAttempt()
    }

    /**
     * 作废当前这一轮开房，并确保那个可能已经挂出去的占位房间有人负责撤销。
     *
     * 顺序要紧：先按**当前**这一轮的结果决定撤不撤，再让轮次作废。
     * 反过来的话，在途请求会被判成「作废」而自行撤销，而这里又读着上一轮的残留
     * 结果再撤一次 —— 两边都以为对方在处理。
     */
    private fun abandonStartAttempt() {
        if (startSettled && startOutcome is StartOutcome.Ok) {
            sessionManager.getToken()?.let { token ->
                lifecycleScope.launch { api.hostStop(token) }
            }
        }

        // 还在途的那一次：轮次一变，它落地时就会自己 hostStop 掉
        startAttempt++
        startSettled = false
        startOutcome = null
    }

    /** 后端没接受这次开房，把已经建立的 Neri 连接收干净 */
    private fun abandonPendingRoom() {
        pendingRoomInfo = null
        HostSession.cancelVerification()
    }

    private fun onHostingStarted(info: RoomInfo) {
        hostReviveCount = 0
        pendingRoomInfo = null
        isVerifyingSecret = false
        etInviteCode.setText("")

        // 进入放歌：复用校验阶段那条 Neri 连接，拉起保活循环和前台服务。
        // 校验阶段缓存的首帧会在这里一并发给服务端（confirm），
        // 那个 pending 占位房间也就此对成员可见。
        HostSession.commitHosting(info)
    }

    /**
     * 关播收口。所有退出路径（用户关播、登出、token 失效、服务端关房）都走这里 ——
     * 只有一处收口才不会漏掉某个入口把前台服务留在那里。
     */
    private fun stopHosting(notifyServer: Boolean) {
        val wasActive = HostSession.isActive

        if (wasActive && notifyServer) {
            suppressRoomClosedUntil = System.currentTimeMillis() + ROOM_CLOSED_SUPPRESS_MS
        }

        verifyJob?.cancel()
        verifyJob = null
        startJob = null
        abandonStartAttempt()
        pendingRoomInfo = null
        isVerifyingSecret = false
        isPublishing = false
        hostReviveCount = 0
        hostRoomLost = false

        HostSession.stop(
            notifyServer = notifyServer,
            reason = if (notifyServer) HostSession.StopReason.USER
            else HostSession.StopReason.ROOM_CLOSED
        )

        // 立刻把画面收回到「空闲」，不等跨境的 room_closed 回声；
        // 随后到达的权威快照会确认同一件事。
        snapshot = snapshot?.copy(room = null)
        hostQq = null
        resetHostUi()
        renderRoom(null)
        refreshMembersTab()
    }

    /** 会话结束后回收「房主专属」的界面状态 */
    private fun resetHostUi() {
        etInviteCode.setText("")
        setPublishControlsEnabled(true, "开启")
        layoutAudioPlayerCard.isVisible = false
        progressTracker.reset()
        pbPlayerProgress.progress = 0
    }

    private fun setPublishControlsEnabled(enabled: Boolean, text: String) {
        btnPublishRoom.isEnabled = enabled
        btnPublishRoom.text = text
        btnPasteInvite.isEnabled = enabled
        etInviteCode.isEnabled = enabled
    }

    // 房主身份持久化（persistHostingRoom / touchHostingRecord / restoreHostingRoom）
    // 已经整体搬进 HostSession —— 它必须活过 Activity 销毁，否则进程被杀之后
    // 就没有依据把房间重新挂回来了。对外只需要 HostSession.restorePersistedRoom()。

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
            showTip("未安装 NeriPlayer")
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
