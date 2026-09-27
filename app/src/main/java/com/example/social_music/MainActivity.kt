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
import com.example.social_music.model.RegisteredMember
import com.example.social_music.model.RoomInfo
import com.example.social_music.net.NeriRealtimeWatcher
import com.example.social_music.net.RoomApiService
import com.example.social_music.utils.AnimationTemplates
import com.example.social_music.utils.CapsuleTipManager
import com.example.social_music.utils.NeriDeepLinkHelper
import com.example.social_music.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // ⚡ 容错率提升为 3 次，防止网络抖动误解散
        private const val ROOM_MISS_THRESHOLD = 3
        private const val MAX_HOST_RETRY = 3
    }

    private val apiService = RoomApiService()
    private lateinit var sessionManager: SessionManager
    private lateinit var tipManager: CapsuleTipManager
    private lateinit var progressTracker: PlaybackProgressTracker

    // ⚡ Neri WebSocket 毫秒级长连接监听器
    private lateinit var neriWatcher: NeriRealtimeWatcher

    private var currentDeepLink: String? = null
    private var currentRoomInfo: RoomInfo? = null
    private var isHosting = false
    private var currentAnimType = AnimationTemplates.ANIM_NONE

    private var isPublishing = false
    private var roomMissCount = 0
    private var hostRetryCount = 0

    // 🎵 记录当前最新的播放指标（用于房主向服务器同步歌曲）
    private var liveSongTitle: String? = null
    private var liveArtist: String? = null
    private var liveCoverUrl: String? = null
    private var liveDurationMs: Long = 0L
    private var liveBasePosMs: Long = 0L
    private var liveIsPlaying: Boolean = true

    // 协程任务控制
    private var initProbeJob: Job? = null
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var fetchMembersJob: Job? = null

    // 👥 成员数据内存缓存与防抖
    private var cachedMembers: List<RegisteredMember>? = null
    private var lastFetchMembersTime: Long = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = SessionManager(this)
        tipManager = CapsuleTipManager(this)

        initViews()
        initWebViewSettings()
        initProgressTracker()
        initRealtimeWatcher()
        setupListeners()

        showLoadingState()
        restoreHostingStateIfAny()
    }

    override fun onResume() {
        super.onResume()
        updateUserUi()

        if (currentRoomInfo == null && currentDeepLink == null) {
            showLoadingState()
        }

        checkAndProbeRoomOnEntry()

        if (layoutMembersTabContent.isVisible) {
            fetchRegisteredMembers()
        }
    }

    override fun onStop() {
        super.onStop()
        // ⚡ 核心修复：如果是房主，切到播放器切歌时，绝对不能停止 WebSocket 监听！
        if (!isHosting) {
            neriWatcher.stopWatching()
        }
        progressTracker.stop()
        initProbeJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null
        fetchMembersJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        neriWatcher.stopWatching()
        stopHeartbeat()
        progressTracker.stop()
        initProbeJob?.cancel()
        pollingJob?.cancel()
        fetchMembersJob?.cancel()

        (webHexLoader.parent as? ViewGroup)?.removeView(webHexLoader)
        webHexLoader.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        webHexLoader.clearHistory()
        webHexLoader.destroy()

        webMembersLoader?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            it.clearHistory()
            it.destroy()
            webMembersLoader = null
        }
    }

    private fun showTip(message: String) = tipManager.showTip(message)
    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

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

    private fun initProgressTracker() {
        progressTracker = PlaybackProgressTracker(
            scope = lifecycleScope,
            onProgressTick = { ratio ->
                pbPlayerProgress.progress = (ratio * 1000).toInt()
            },
            onSongFinished = {}
        )
    }

    private fun initRealtimeWatcher() {
        neriWatcher = NeriRealtimeWatcher(
            context = this,
            onPlaybackUpdate = { songTitle, artist, coverUrl, durationMs, basePosMs, baseTimestampMs, playbackRate, isPlaying ->
                if (!songTitle.isNullOrBlank()) {
                    layoutAudioPlayerCard.isVisible = true

                    tvPlayerSongTitle.text = songTitle
                    tvPlayerArtist.text = artist ?: "NeriPlayer"
                    tvPlayerSongTitle.isSelected = true

                    val isNewSong = (songTitle != liveSongTitle)

                    liveSongTitle = songTitle
                    liveArtist = artist
                    liveCoverUrl = coverUrl
                    liveDurationMs = durationMs
                    liveBasePosMs = basePosMs
                    liveIsPlaying = isPlaying

                    if (!coverUrl.isNullOrEmpty()) {
                        ivPlayerAlbumCover.load(coverUrl) {
                            crossfade(true)
                            placeholder(R.drawable.bg_avatar_gray)
                            error(R.drawable.bg_avatar_gray)
                            transformations(CircleCropTransformation())
                        }
                    } else {
                        ivPlayerAlbumCover.setImageResource(R.drawable.bg_avatar_gray)
                    }

                    progressTracker.updateMetrics(
                        durationMs = durationMs,
                        basePositionMs = basePosMs,
                        baseTimestampMs = baseTimestampMs,
                        playbackRate = playbackRate,
                        isPlaying = isPlaying
                    )

                    // ⚡ 房主切歌毫秒级主动同步服务器 Redis
                    if (isHosting && isNewSong) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val fullSongName = if (!artist.isNullOrEmpty()) "$songTitle - $artist" else songTitle
                            apiService.sendHeartbeat(
                                username = sessionManager.getUsername(),
                                currentSong = fullSongName,
                                currentCover = coverUrl,
                                durationMs = durationMs,
                                basePositionMs = basePosMs,
                                isPlaying = isPlaying
                            )
                        }
                    }
                } else {
                    progressTracker.stop()
                    layoutAudioPlayerCard.isVisible = false
                    liveSongTitle = null
                }
            },
            onRoomClosed = {
                if (currentRoomInfo != null || currentDeepLink != null) {
                    showTip("房主已结束放歌")
                    currentRoomInfo = null
                    currentDeepLink = null
                    if (isHosting) {
                        isHosting = false
                        stopHeartbeat()
                        sessionManager.clearHostingRoom()
                    }
                    updateRoomUi(false, null, null, null, null)
                }
            }
        )
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

    private fun showLoadingState() {
        switchAnimation(AnimationTemplates.ANIM_SPINNER)
        tvHostMessage.isVisible = false
        tvHostMessage.text = ""
        layoutAudioPlayerCard.isVisible = false
        ivHostAvatar.isVisible = false
        btnJoin.isVisible = false
        layoutHostSection.isVisible = false
    }

    private fun showMembersLoading() {
        tvMemberCountBadge.isVisible = false
        layoutMembersContainer.removeAllViews()

        val loader = getOrCreateMembersLoader()
        (loader.parent as? ViewGroup)?.removeView(loader)
        loader.loadDataWithBaseURL(null, AnimationTemplates.getDotSpinnerHtml(), "text/html", "UTF-8", null)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp2px(260)
        ).apply {
            gravity = Gravity.CENTER
            topMargin = dp2px(40)
        }
        layoutMembersContainer.addView(loader, params)
    }

    private fun updateUserUi() {
        if (sessionManager.isLoggedIn()) {
            tvCurrentUserName.text = sessionManager.getUsername()
            btnLogout.isVisible = false

            val avatarUri = sessionManager.getAvatarUri()
            if (!avatarUri.isNullOrEmpty()) {
                ivUserAvatar.load(avatarUri) {
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar_gray)
                    error(R.drawable.bg_avatar_gray)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivUserAvatar.setImageResource(R.drawable.bg_avatar_gray)
            }
        } else {
            tvCurrentUserName.text = "未登录"
            btnLogout.isVisible = true
            btnLogout.text = "登录"
            ivUserAvatar.setImageResource(R.drawable.bg_avatar_gray)
        }
    }

    private fun setupListeners() {
        tabMusic.setOnClickListener { switchTab(isMusicTab = true) }
        tabMembers.setOnClickListener {
            switchTab(isMusicTab = false)
            fetchRegisteredMembers()
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
                if (isHosting) {
                    lifecycleScope.launch {
                        apiService.broadcastRoom("stop", sessionManager.getUsername(), currentRoomInfo)
                    }
                }
                sessionManager.clearSession()
                sessionManager.clearHostingRoom()
                isHosting = false
                stopHeartbeat()
                neriWatcher.stopWatching()
                cachedMembers = null
                lastFetchMembersTime = 0L
                fetchMembersJob?.cancel()
                liveSongTitle = null

                updateUserUi()
                updateRoomUi(false, null, null, null, null)
                showTip("已退出登录")
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
            val link = currentDeepLink
            if (link.isNullOrEmpty()) {
                showTip("当前无可用房间链接")
                return@setOnClickListener
            }
            verifyAndJoinRoom(link)
        }

        btnPublishRoom.setOnClickListener {
            if (!sessionManager.isLoggedIn()) {
                showTip("请先登录")
                return@setOnClickListener
            }

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

    private fun fetchRegisteredMembers(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()

        if (!cachedMembers.isNullOrEmpty()) {
            renderMembersList(cachedMembers!!)
            if (!forceRefresh && (now - lastFetchMembersTime < 5000L)) {
                return
            }
        } else {
            showMembersLoading()
        }

        fetchMembersJob?.cancel()
        fetchMembersJob = lifecycleScope.launch {
            try {
                val token = sessionManager.getToken()
                val result = apiService.fetchMemberList(token)

                result.onSuccess { list ->
                    val memberList = list.toMutableList()

                    if (sessionManager.isLoggedIn()) {
                        val myName = sessionManager.getUsername()
                        if (memberList.none { it.username.equals(myName, ignoreCase = true) }) {
                            memberList.add(
                                0,
                                RegisteredMember(
                                    username = myName,
                                    avatarUrl = sessionManager.getAvatarUri().orEmpty(),
                                    isHosting = isHosting
                                )
                            )
                        }
                    }

                    cachedMembers = memberList
                    lastFetchMembersTime = System.currentTimeMillis()
                    renderMembersList(memberList)
                }.onFailure { error ->
                    Log.w(TAG, "拉取成员失败: ${error.message}")
                    if (cachedMembers.isNullOrEmpty()) {
                        if (sessionManager.isLoggedIn()) {
                            renderMembersList(
                                listOf(
                                    RegisteredMember(
                                        username = sessionManager.getUsername(),
                                        avatarUrl = sessionManager.getAvatarUri().orEmpty(),
                                        isHosting = isHosting
                                    )
                                )
                            )
                        } else {
                            renderMembersList(emptyList())
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "拉取成员异常", e)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderMembersList(members: List<RegisteredMember>) {
        tvMemberCountBadge.isVisible = true
        tvMemberCountBadge.text = "${members.size} 位已认证"
        layoutMembersContainer.removeAllViews()

        val myUsername = sessionManager.getUsername()

        for (member in members) {
            val itemView = layoutInflater.inflate(R.layout.item_user_member, layoutMembersContainer, false)
            val ivAvatar = itemView.findViewById<ShapeableImageView>(R.id.ivMemberAvatar)
            val tvName = itemView.findViewById<TextView>(R.id.tvMemberName)
            val tvMeBadge = itemView.findViewById<TextView>(R.id.tvMeBadge)
            val tvHostingBadge = itemView.findViewById<TextView>(R.id.tvHostingBadge)

            tvName.text = member.username
            tvMeBadge.isVisible = sessionManager.isLoggedIn() && member.username == myUsername
            tvHostingBadge.isVisible = member.isHosting

            if (member.avatarUrl.isNotEmpty()) {
                ivAvatar.load(member.avatarUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar_gray)
                    error(R.drawable.bg_avatar_gray)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivAvatar.setImageResource(R.drawable.bg_avatar_gray)
            }
            layoutMembersContainer.addView(itemView)
        }
    }

    private fun persistHostingRoom(info: RoomInfo) {
        val json = JSONObject().apply {
            put("owner", sessionManager.getUsername())
            put("roomId", info.roomId ?: "")
            put("secret", info.secret ?: "")
            put("deepLink", info.rawUri)
            put("serverUrl", info.serverUrl ?: RoomApiService.DEFAULT_NERI_SERVER)
            put("inviter", info.inviter ?: "")
            put("currentSong", info.currentSong ?: "")
            put("currentCover", info.currentCover ?: "")
        }
        sessionManager.saveHostingRoom(json.toString())
    }

    private fun restoreHostingRoom(): RoomInfo? {
        val raw = sessionManager.getHostingRoom() ?: return null
        return try {
            val json = JSONObject(raw)
            val owner = json.optString("owner", "")
            if (owner.isNotEmpty() && owner != sessionManager.getUsername()) {
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
                serverUrl = json.optString("serverUrl", "").ifEmpty { null },
                currentSong = json.optString("currentSong", "").ifEmpty { null },
                currentCover = json.optString("currentCover", "").ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreHostingStateIfAny() {
        if (isHosting) return
        val restored = restoreHostingRoom() ?: return
        Log.i(TAG, "恢复房主身份 roomId=${restored.roomId}")
        currentRoomInfo = restored
        isHosting = true
        startHeartbeat()
    }

    private fun ensureRoomUiVisible(data: RoomApiService.StatusResult) {
        switchAnimation(AnimationTemplates.ANIM_HEX)
        tvHostMessage.isVisible = true

        val myUsername = sessionManager.getUsername()
        val isMe = sessionManager.isLoggedIn() && (myUsername == data.publisher || myUsername == data.inviter)
        tvHostMessage.text = if (isMe) "你正在放歌" else "${data.inviter ?: "群友"} 正在放歌"

        ivHostAvatar.isVisible = true
        if (!data.hostAvatarUrl.isNullOrEmpty()) {
            ivHostAvatar.load(data.hostAvatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_gray)
                error(R.drawable.bg_avatar_gray)
                transformations(CircleCropTransformation())
            }
        } else {
            ivHostAvatar.setImageResource(R.drawable.bg_avatar_gray)
        }

        btnJoin.isVisible = !isMe
        layoutHostSection.isVisible = false

        if (!layoutAudioPlayerCard.isVisible && !data.currentSong.isNullOrBlank()) {
            applyRoomData(data)
        }
    }

    private fun checkAndProbeRoomOnEntry() {
        initProbeJob?.cancel()
        roomMissCount = 0
        initProbeJob = lifecycleScope.launch {
            val result = apiService.getRoomStatus()
            result.onSuccess { data ->
                if (data.exists && !data.deepLink.isNullOrEmpty()) {
                    val info = NeriDeepLinkHelper.parseInvitation(data.deepLink)?.copy(
                        currentSong = data.currentSong,
                        currentCover = data.currentCover
                    )
                    val isNewRoom = currentRoomInfo == null || currentRoomInfo?.roomId != info?.roomId

                    val myUsername = sessionManager.getUsername()
                    val isMe = sessionManager.isLoggedIn() && (myUsername == data.publisher || myUsername == data.inviter)

                    if (isMe && info != null) {
                        isHosting = true
                        startHeartbeat()
                    }

                    currentRoomInfo = info

                    if (isNewRoom) {
                        applyRoomData(data)
                    } else {
                        ensureRoomUiVisible(data)
                        info?.let {
                            val serverUrl = it.serverUrl ?: RoomApiService.DEFAULT_NERI_SERVER
                            if (!it.roomId.isNullOrEmpty() && !it.secret.isNullOrEmpty()) {
                                neriWatcher.startWatching(serverUrl, it.roomId, it.secret, sessionManager.getUsername())
                            }
                        }
                    }
                } else {
                    val info = currentRoomInfo ?: restoreHostingRoom()
                    if (info != null) {
                        currentRoomInfo = info
                        isHosting = true
                        startHeartbeat()
                        executeBroadcastAction(info, action = "start", isResume = true)
                    } else {
                        isHosting = false
                        stopHeartbeat()
                        updateRoomUi(false, null, null, null, null)
                    }
                }
                startPollingRoomStatus()
            }.onFailure {
                if (!isHosting) {
                    updateRoomUi(false, null, null, null, null)
                }
                startPollingRoomStatus()
            }
        }
    }

    private fun applyRoomData(data: RoomApiService.StatusResult) {
        updateRoomUi(
            exists = true,
            inviter = data.inviter,
            publisher = data.publisher,
            hostAvatarUrl = data.hostAvatarUrl,
            deepLink = data.deepLink,
            currentSong = data.currentSong,
            currentCover = data.currentCover,
            durationMs = data.durationMs,
            basePositionMs = data.basePositionMs,
            baseTimestampMs = data.baseTimestampMs,
            playbackRate = data.playbackRate,
            isPlaying = data.isPlaying
        )

        val info = currentRoomInfo
        if (data.exists && info != null && !info.roomId.isNullOrEmpty() && !info.secret.isNullOrEmpty()) {
            val serverUrl = info.serverUrl ?: RoomApiService.DEFAULT_NERI_SERVER
            neriWatcher.startWatching(serverUrl, info.roomId, info.secret, sessionManager.getUsername())
        }
    }

    private fun updateRoomUi(
        exists: Boolean,
        inviter: String?,
        publisher: String?,
        hostAvatarUrl: String?,
        deepLink: String?,
        currentSong: String? = null,
        currentCover: String? = null,
        durationMs: Long = 0L,
        basePositionMs: Long = 0L,
        baseTimestampMs: Long = 0L,
        playbackRate: Double = 1.0,
        isPlaying: Boolean = true
    ) {
        if (!exists && isPublishing) return

        switchAnimation(AnimationTemplates.ANIM_HEX)
        tvHostMessage.isVisible = true

        if (!exists) {
            neriWatcher.stopWatching()
            currentDeepLink = null
            currentRoomInfo = null
            isHosting = false
            stopHeartbeat()
            progressTracker.stop()

            tvHostMessage.text = "空闲"
            layoutAudioPlayerCard.isVisible = false
            ivHostAvatar.isVisible = false
            btnJoin.isVisible = false
            layoutHostSection.isVisible = true
        } else {
            currentDeepLink = deepLink
            val myUsername = sessionManager.getUsername()
            val isMe = sessionManager.isLoggedIn() && (myUsername == publisher || myUsername == inviter)

            tvHostMessage.text = if (isMe) "你正在放歌" else "${inviter ?: "群友"} 正在放歌"

            if (!currentSong.isNullOrBlank()) {
                layoutAudioPlayerCard.isVisible = true

                val parts = currentSong.split(" - ")
                val title = if (parts.size > 1) parts.dropLast(1).joinToString(" - ") else currentSong
                val artist = if (parts.size > 1) parts.last() else "NeriPlayer"

                tvPlayerSongTitle.text = title
                tvPlayerArtist.text = artist
                tvPlayerSongTitle.isSelected = true

                if (!currentCover.isNullOrEmpty()) {
                    ivPlayerAlbumCover.load(currentCover) {
                        crossfade(true)
                        placeholder(R.drawable.bg_avatar_gray)
                        error(R.drawable.bg_avatar_gray)
                        transformations(CircleCropTransformation())
                    }
                } else {
                    ivPlayerAlbumCover.setImageResource(R.drawable.bg_avatar_gray)
                }

                progressTracker.updateMetrics(
                    durationMs = durationMs,
                    basePositionMs = basePositionMs,
                    baseTimestampMs = baseTimestampMs,
                    playbackRate = playbackRate,
                    isPlaying = isPlaying
                )
            } else {
                progressTracker.stop()
                layoutAudioPlayerCard.isVisible = false
            }

            ivHostAvatar.isVisible = true
            if (!hostAvatarUrl.isNullOrEmpty()) {
                ivHostAvatar.load(hostAvatarUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar_gray)
                    error(R.drawable.bg_avatar_gray)
                    transformations(CircleCropTransformation())
                }
            } else {
                ivHostAvatar.setImageResource(R.drawable.bg_avatar_gray)
            }

            btnJoin.isVisible = !isMe
            layoutHostSection.isVisible = false
        }
    }

    private fun verifyAndStartHosting(info: RoomInfo) {
        val roomId = info.roomId?.trim()
        val secret = info.secret?.trim()

        if (roomId.isNullOrEmpty() || !roomId.matches(Regex("^[a-zA-Z0-9]{6}$")) || secret.isNullOrEmpty()) {
            showTip("口令格式错误：未解析到合法的6位房间号或密钥！")
            return
        }

        isPublishing = true
        btnPublishRoom.isEnabled = false
        btnPublishRoom.text = "开启中..."
        btnPasteInvite.isEnabled = false
        etInviteCode.isEnabled = false

        executeBroadcastAction(info, action = "start")
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            while (isActive && isHosting) {
                delay(5.seconds)
                val fullSongName = if (!liveSongTitle.isNullOrEmpty()) {
                    if (!liveArtist.isNullOrEmpty()) "$liveSongTitle - $liveArtist" else liveSongTitle
                } else null

                val code = apiService.sendHeartbeat(
                    username = sessionManager.getUsername(),
                    currentSong = fullSongName,
                    currentCover = liveCoverUrl,
                    durationMs = liveDurationMs,
                    basePositionMs = liveBasePosMs,
                    isPlaying = liveIsPlaying
                )

                if (code == 404) {
                    handleRoomLostWhileHosting()
                } else if (code in 200..299) {
                    hostRetryCount = 0
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleRoomLostWhileHosting() {
        if (!isHosting || isPublishing) return

        val info = currentRoomInfo ?: restoreHostingRoom()
        if (info == null || hostRetryCount >= MAX_HOST_RETRY) {
            isHosting = false
            stopHeartbeat()
            neriWatcher.stopWatching()
            sessionManager.clearHostingRoom()
            updateRoomUi(false, null, null, null, null)
            showTip("房间已解散")
            return
        }

        hostRetryCount++
        showTip("正在重连房间...")
        currentRoomInfo = info
        executeBroadcastAction(info, action = "start", isResume = true)
    }

    private fun startPollingRoomStatus() {
        if (pollingJob?.isActive == true) return
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val interval = if (currentDeepLink != null) 6.seconds else 10.seconds
                delay(interval)
                fetchRoomStatusSequential()
            }
        }
    }

    private suspend fun fetchRoomStatusSequential() {
        if (isPublishing) return

        val result = apiService.getRoomStatus()
        result.onSuccess { data ->
            if (isPublishing) return@onSuccess

            if (data.exists && !data.deepLink.isNullOrEmpty()) {
                roomMissCount = 0
                val info = NeriDeepLinkHelper.parseInvitation(data.deepLink)?.copy(
                    currentSong = data.currentSong,
                    currentCover = data.currentCover
                )
                val isNewRoom = currentRoomInfo == null || currentRoomInfo?.roomId != info?.roomId
                val isSongChanged = currentRoomInfo?.currentSong != data.currentSong

                if (isNewRoom) {
                    currentRoomInfo = info
                    applyRoomData(data)
                } else if (isSongChanged && !isHosting) {
                    currentRoomInfo = info
                    applyRoomData(data)
                }
            } else {
                // ⚡ 核心修复：如果是房主本人，严禁被单次轮询判定解散（房主生命线完全由心跳决定）
                if (isHosting) return@onSuccess

                roomMissCount++
                if (roomMissCount >= ROOM_MISS_THRESHOLD) {
                    currentRoomInfo = null
                    currentDeepLink = null
                    neriWatcher.stopWatching()
                    updateRoomUi(false, null, null, null, null)
                }
            }
        }
    }

    private fun executeBroadcastAction(info: RoomInfo?, action: String, isResume: Boolean = false) {
        lifecycleScope.launch {
            val result = apiService.broadcastRoom(action, sessionManager.getUsername(), info, isResume)

            if (action == "start") {
                isPublishing = false
                btnPublishRoom.isEnabled = true
                btnPublishRoom.text = "开启"
                btnPasteInvite.isEnabled = true
                etInviteCode.isEnabled = true

                if (!result.isSuccess) {
                    sessionManager.clearHostingRoom()
                    isHosting = false
                    stopHeartbeat()
                    neriWatcher.stopWatching()
                    updateRoomUi(false, null, null, null, null)
                    showTip(result.message ?: "开启失败(${result.code})")
                } else {
                    etInviteCode.setText("")
                    isHosting = true
                    roomMissCount = 0
                    hostRetryCount = 0

                    val statusData = result.statusData
                    val updatedInfo = info?.copy(
                        currentSong = statusData?.currentSong,
                        currentCover = statusData?.currentCover
                    )
                    currentRoomInfo = updatedInfo
                    updatedInfo?.let { persistHostingRoom(it) }

                    updateRoomUi(
                        exists = true,
                        inviter = info?.inviter ?: sessionManager.getUsername(),
                        publisher = sessionManager.getUsername(),
                        hostAvatarUrl = sessionManager.getAvatarUri(),
                        deepLink = info?.rawUri,
                        currentSong = statusData?.currentSong,
                        currentCover = statusData?.currentCover
                    )

                    info?.let {
                        val serverUrl = it.serverUrl ?: RoomApiService.DEFAULT_NERI_SERVER
                        if (!it.roomId.isNullOrEmpty() && !it.secret.isNullOrEmpty()) {
                            neriWatcher.startWatching(serverUrl, it.roomId, it.secret, sessionManager.getUsername())
                        }
                    }

                    startHeartbeat()

                    if (!isResume) {
                        showTip("房间上线成功！")
                    }
                }
            }
        }
    }
}