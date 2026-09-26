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
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.social_music.model.RegisteredMember
import com.example.social_music.model.RoomInfo
import com.example.social_music.utils.AnimationTemplates
import com.example.social_music.utils.CapsuleTipManager
import com.example.social_music.utils.NeriDeepLinkHelper
import com.example.social_music.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_URL = "https://www.white667.xyz/api"
        private const val DEFAULT_NERI_SERVER = "https://neriplayer.hancat.work"

        private const val ROOM_MISS_THRESHOLD = 3
        private const val MAX_HOST_RETRY = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private lateinit var sessionManager: SessionManager
    private lateinit var tipManager: CapsuleTipManager

    private var currentDeepLink: String? = null
    private var currentRoomInfo: RoomInfo? = null
    private var isHosting = false
    private var currentAnimType = AnimationTemplates.ANIM_NONE

    private var isPublishing = false
    private var roomMissCount = 0
    private var hostRetryCount = 0

    private var initProbeJob: Job? = null
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null

    // ⏱ 播放进度平滑推算锚点
    private lateinit var pbPlayerProgress: ProgressBar
    private var basePositionMs: Long = 0L
    private var baseTimestampMs: Long = 0L
    private var durationMs: Long = 0L
    private var playbackRate: Double = 1.0
    private var isPlaying: Boolean = false
    private var progressTickerJob: Job? = null

    // 全局顶部控件
    private lateinit var ivUserAvatar: ShapeableImageView
    private lateinit var tvCurrentUserName: TextView
    private lateinit var btnLogout: TextView

    // Tab 容器与切换按钮
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

    // 🎵 播放器卡片控件
    private lateinit var layoutAudioPlayerCard: View
    private lateinit var ivPlayerAlbumCover: ShapeableImageView
    private lateinit var tvPlayerSongTitle: TextView
    private lateinit var tvPlayerArtist: TextView

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
        setupListeners()

        showLoadingState()
        restoreHostingStateIfAny()
    }

    override fun onResume() {
        super.onResume()
        updateUserUi()
        showLoadingState()
        checkAndProbeRoomOnEntry()

        if (layoutMembersTabContent.visibility == View.VISIBLE) {
            fetchRegisteredMembers()
        }
    }

    override fun onStop() {
        super.onStop()
        stopProgressTicker()
        initProbeJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        stopProgressTicker()
        initProbeJob?.cancel()
        pollingJob?.cancel()

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

        // 播放器卡片绑定
        layoutAudioPlayerCard = findViewById(R.id.layoutAudioPlayerCard)
        ivPlayerAlbumCover = findViewById(R.id.ivPlayerAlbumCover)
        tvPlayerSongTitle = findViewById(R.id.tvPlayerSongTitle)
        tvPlayerArtist = findViewById(R.id.tvPlayerArtist)
        pbPlayerProgress = findViewById(R.id.pbPlayerProgress)
        pbPlayerProgress.max = 1000 // 细分 1000 档位，平滑度极高

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
        tvHostMessage.visibility = View.GONE
        tvHostMessage.text = ""
        layoutAudioPlayerCard.visibility = View.GONE
        ivHostAvatar.visibility = View.GONE
        btnJoin.visibility = View.GONE
        layoutHostSection.visibility = View.GONE
    }

    private fun showMembersLoading() {
        tvMemberCountBadge.visibility = View.GONE
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
            btnLogout.visibility = View.GONE

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
            btnLogout.visibility = View.VISIBLE
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
                sessionManager.clearSession()
                updateUserUi()
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
        if (isMusicTab) {
            layoutMusicTabContent.visibility = View.VISIBLE
            layoutMembersTabContent.visibility = View.GONE
            tvTabMusic.setTextColor(0xFF0F172A.toInt())
            tvTabMembers.setTextColor(0xFF94A3B8.toInt())
        } else {
            layoutMusicTabContent.visibility = View.GONE
            layoutMembersTabContent.visibility = View.VISIBLE
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
        btnJoin.isEnabled = false
        btnJoin.text = "核验中..."

        lifecycleScope.launch(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/room/status?force=true")
                .get()
                .build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                val isOk = response.isSuccessful

                withContext(Dispatchers.Main) {
                    btnJoin.isEnabled = true
                    btnJoin.text = "加入"

                    if (isOk && body.isNotEmpty()) {
                        val json = JSONObject(body)
                        val exists = json.optBoolean("exists", false)

                        if (exists) {
                            val launched = NeriDeepLinkHelper.launchPlayer(this@MainActivity, link)
                            if (!launched) {
                                showTip("未找到 NeriPlayer，请确认已安装！")
                            }
                        } else {
                            showTip("房主已结束放歌或房间已解散")
                            updateRoomUi(false, null, null, null, null, null, null)
                        }
                    } else {
                        showTip("核验失败，请重试")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "核验请求异常", e)
                withContext(Dispatchers.Main) {
                    btnJoin.isEnabled = true
                    btnJoin.text = "加入"
                    showTip("网络异常，无法核验房间状态")
                }
            }
        }
    }

    private fun fetchRegisteredMembers() {
        showMembersLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val token = sessionManager.getToken()
            val requestBuilder = Request.Builder()
                .url("$BASE_URL/user/list")
                .get()

            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            try {
                val response = client.newCall(requestBuilder.build()).execute()
                val body = response.body?.string().orEmpty()
                val memberList = mutableListOf<RegisteredMember>()

                if (response.isSuccessful && body.isNotEmpty()) {
                    val trimmed = body.trim()
                    val dataArray = if (trimmed.startsWith("[")) {
                        org.json.JSONArray(trimmed)
                    } else {
                        val json = JSONObject(trimmed)
                        json.optJSONArray("data")
                            ?: json.optJSONArray("users")
                            ?: json.optJSONArray("list")
                            ?: json.optJSONArray("members")
                    }

                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val name = obj.optString("username", obj.optString("name", "")).trim()
                            if (name.isNotEmpty()) {
                                memberList.add(
                                    RegisteredMember(
                                        username = name,
                                        avatarUrl = obj.optString("avatarUrl", obj.optString("avatar", "")),
                                        isHosting = obj.optBoolean("isHosting", false)
                                    )
                                )
                            }
                        }
                    }
                }

                if (sessionManager.isLoggedIn()) {
                    val myName = sessionManager.getUsername()
                    val hasMe = memberList.any { it.username.equals(myName, ignoreCase = true) }
                    if (!hasMe) {
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

                withContext(Dispatchers.Main) {
                    renderMembersList(memberList)
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取成员列表异常", e)
                withContext(Dispatchers.Main) {
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
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderMembersList(members: List<RegisteredMember>) {
        tvMemberCountBadge.visibility = View.VISIBLE
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
            tvMeBadge.visibility = if (sessionManager.isLoggedIn() && member.username == myUsername) View.VISIBLE else View.GONE
            tvHostingBadge.visibility = if (member.isHosting) View.VISIBLE else View.GONE

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
            put("serverUrl", info.serverUrl ?: DEFAULT_NERI_SERVER)
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
        } catch (e: Exception) {
            Log.w(TAG, "房主身份恢复失败", e)
            null
        }
    }

    private fun restoreHostingStateIfAny() {
        if (isHosting) return
        val restored = restoreHostingRoom() ?: return
        Log.i(TAG, "检测到本机上次正在放歌，恢复房主身份 roomId=${restored.roomId}")
        currentRoomInfo = restored
        isHosting = true
        startHeartbeat()
    }

    private fun checkAndProbeRoomOnEntry() {
        initProbeJob?.cancel()
        roomMissCount = 0
        initProbeJob = lifecycleScope.launch(Dispatchers.IO) {
            val targetUrl = if (isHosting) "$BASE_URL/room/status?force=true" else "$BASE_URL/room/status"
            val request = Request.Builder().url(targetUrl).get().build()

            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (response.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val exists = json.optBoolean("exists", false)
                    val inviter = json.optString("inviter", "")
                    val publisher = json.optString("publisher", "")
                    val hostAvatarUrl = json.optString("hostAvatarUrl", "")
                    val deepLink = json.optString("deepLink", "")
                    val currentSong = json.optString("currentSong", "").ifEmpty { null }
                    val currentCover = json.optString("currentCover", "").ifEmpty { null }
                    val durationMs = json.optLong("durationMs", 0L)
                    val basePositionMs = json.optLong("basePositionMs", 0L)
                    val baseTimestampMs = json.optLong("baseTimestampMs", System.currentTimeMillis())
                    val playbackRate = json.optDouble("playbackRate", 1.0)
                    val isPlaying = json.optBoolean("isPlaying", true)

                    withContext(Dispatchers.Main) {
                        if (exists && deepLink.isNotEmpty()) {
                            val info = NeriDeepLinkHelper.parseInvitation(deepLink)?.copy(
                                currentSong = currentSong,
                                currentCover = currentCover
                            )
                            currentRoomInfo = info
                            val myUsername = sessionManager.getUsername()
                            val isMe = sessionManager.isLoggedIn() && (myUsername == publisher || myUsername == inviter)

                            if (isMe && info != null) {
                                isHosting = true
                                startHeartbeat()
                            }
                            updateRoomUi(
                                exists = true,
                                inviter = inviter,
                                publisher = publisher,
                                hostAvatarUrl = hostAvatarUrl,
                                deepLink = deepLink,
                                currentSong = currentSong,
                                currentCover = currentCover,
                                durationMs = durationMs,
                                basePositionMs = basePositionMs,
                                baseTimestampMs = baseTimestampMs,
                                playbackRate = playbackRate,
                                isPlaying = isPlaying
                            )
                        } else {
                            val info = currentRoomInfo ?: restoreHostingRoom()
                            if (info != null) {
                                Log.w(TAG, "服务端房间已失效，自动重新开播 roomId=${info.roomId}")
                                currentRoomInfo = info
                                isHosting = true
                                startHeartbeat()
                                postRoomState(info, action = "start", isResume = true)
                            } else {
                                isHosting = false
                                stopHeartbeat()
                                updateRoomUi(false, null, null, null, null, null, null)
                            }
                        }
                        startPollingRoomStatus()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isHosting) {
                            updateRoomUi(false, null, null, null, null, null, null)
                        } else {
                            val myUsername = sessionManager.getUsername()
                            updateRoomUi(
                                exists = true,
                                inviter = currentRoomInfo?.inviter ?: myUsername,
                                publisher = myUsername,
                                hostAvatarUrl = sessionManager.getAvatarUri(),
                                deepLink = currentRoomInfo?.rawUri,
                                currentSong = currentRoomInfo?.currentSong,
                                currentCover = currentRoomInfo?.currentCover
                            )
                        }
                        startPollingRoomStatus()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "探测异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!isHosting) {
                        updateRoomUi(false, null, null, null, null, null, null)
                    } else {
                        val myUsername = sessionManager.getUsername()
                        updateRoomUi(
                            exists = true,
                            inviter = currentRoomInfo?.inviter ?: myUsername,
                            publisher = myUsername,
                            hostAvatarUrl = sessionManager.getAvatarUri(),
                            deepLink = currentRoomInfo?.rawUri,
                            currentSong = currentRoomInfo?.currentSong,
                            currentCover = currentRoomInfo?.currentCover
                        )
                    }
                    startPollingRoomStatus()
                }
            }
        }
    }

    // 🚀 本地平滑推进与自动切歌
    private fun startProgressTicker() {
        if (progressTickerJob?.isActive == true) return
        progressTickerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                updateProgressSmoothly()
                delay(500)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    private fun updateProgressSmoothly() {
        if (durationMs <= 0) {
            pbPlayerProgress.progress = 0
            return
        }

        val now = System.currentTimeMillis()
        val currentPosition = if (isPlaying && baseTimestampMs > 0) {
            val elapsed = ((now - baseTimestampMs) * playbackRate).toLong()
            (basePositionMs + elapsed).coerceAtLeast(0L)
        } else {
            basePositionMs
        }

        // 🎵 自然播完：达到或超过歌曲总时长，触发拉取下一首！
        if (currentPosition >= durationMs && isPlaying) {
            pbPlayerProgress.progress = 1000
            lifecycleScope.launch(Dispatchers.IO) {
                fetchRoomStatusSequential()
            }
            return
        }

        val ratio = (currentPosition.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        pbPlayerProgress.progress = (ratio * 1000).toInt()
    }

    @SuppressLint("SetTextI18n")
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
        tvHostMessage.visibility = View.VISIBLE

        if (!exists) {
            currentDeepLink = null
            currentRoomInfo = null
            isHosting = false
            stopHeartbeat()
            stopProgressTicker()

            tvHostMessage.text = "空闲"
            layoutAudioPlayerCard.visibility = View.GONE
            ivHostAvatar.visibility = View.GONE
            btnJoin.visibility = View.GONE
            layoutHostSection.visibility = View.VISIBLE
        } else {
            currentDeepLink = deepLink
            val myUsername = sessionManager.getUsername()
            val isMe = sessionManager.isLoggedIn() && (myUsername == publisher || myUsername == inviter)

            tvHostMessage.text = if (isMe) "你正在放歌" else "${inviter ?: "群友"} 正在放歌"

            // 🎵 渲染 Spotify 风格暗黑卡片
            if (!currentSong.isNullOrBlank()) {
                layoutAudioPlayerCard.visibility = View.VISIBLE

                val parts = currentSong.split(" - ")
                val songTitle = parts.getOrNull(0) ?: currentSong
                val songArtist = parts.getOrNull(1) ?: "NeriPlayer 同步中"

                tvPlayerSongTitle.text = songTitle
                tvPlayerArtist.text = songArtist
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

                // 注入并启动平滑计算
                this.durationMs = durationMs
                this.basePositionMs = basePositionMs
                this.baseTimestampMs = baseTimestampMs
                this.playbackRate = playbackRate
                this.isPlaying = isPlaying

                updateProgressSmoothly()
                startProgressTicker()
            } else {
                stopProgressTicker()
                layoutAudioPlayerCard.visibility = View.GONE
            }

            ivHostAvatar.visibility = View.VISIBLE
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

            btnJoin.visibility = if (isMe) View.GONE else View.VISIBLE
            layoutHostSection.visibility = View.GONE
        }
    }

    private fun verifyAndStartHosting(info: RoomInfo) {
        val roomId = info.roomId?.trim()
        val secret = info.secret?.trim()

        val isRoomIdValid = !roomId.isNullOrEmpty() && roomId.matches(Regex("^[a-zA-Z0-9]{6}$"))
        val isSecretValid = !secret.isNullOrEmpty()

        if (!isRoomIdValid || !isSecretValid) {
            showTip("口令格式错误：未解析到合法的6位房间号或密钥！")
            return
        }

        isPublishing = true
        btnPublishRoom.isEnabled = false
        btnPublishRoom.text = "开启中..."
        btnPasteInvite.isEnabled = false
        etInviteCode.isEnabled = false

        postRoomState(info, action = "start")
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isHosting) {
                delay(5.seconds)
                sendHeartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeat() {
        val json = JSONObject().apply {
            put("action", "heartbeat")
            put("username", sessionManager.getUsername())
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$BASE_URL/room/broadcast").post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "心跳网络异常", e)
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()

                if (code == 404) {
                    runOnUiThread { handleRoomLostWhileHosting() }
                } else if (code in 200..299) {
                    runOnUiThread { hostRetryCount = 0 }
                }
            }
        })
    }

    private fun handleRoomLostWhileHosting() {
        if (!isHosting || isPublishing) return

        val info = currentRoomInfo ?: restoreHostingRoom()
        if (info == null || hostRetryCount >= MAX_HOST_RETRY) {
            isHosting = false
            stopHeartbeat()
            sessionManager.clearHostingRoom()
            updateRoomUi(false, null, null, null, null, null, null)
            showTip("房间已解散")
            return
        }

        hostRetryCount++
        Log.w(TAG, "连接中断第 $hostRetryCount 次")
        showTip("房正在重连")
        currentRoomInfo = info
        postRoomState(info, action = "start", isResume = true)
    }

    private fun startPollingRoomStatus() {
        if (pollingJob?.isActive == true) return
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val pollInterval = if (currentDeepLink != null) 5000L else 10000L
                delay(pollInterval)
                fetchRoomStatusSequential()
            }
        }
    }

    private suspend fun fetchRoomStatusSequential() = withContext(Dispatchers.IO) {
        if (isPublishing) return@withContext

        val request = Request.Builder().url("$BASE_URL/room/status").get().build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val exists = json.optBoolean("exists", false)
                val inviter = json.optString("inviter", "")
                val publisher = json.optString("publisher", "")
                val hostAvatarUrl = json.optString("hostAvatarUrl", "")
                val deepLink = json.optString("deepLink", "")
                val currentSong = json.optString("currentSong", "").ifEmpty { null }
                val currentCover = json.optString("currentCover", "").ifEmpty { null }
                val durationMs = json.optLong("durationMs", 0L)
                val basePositionMs = json.optLong("basePositionMs", 0L)
                val baseTimestampMs = json.optLong("baseTimestampMs", System.currentTimeMillis())
                val playbackRate = json.optDouble("playbackRate", 1.0)
                val isPlaying = json.optBoolean("isPlaying", true)

                withContext(Dispatchers.Main) {
                    if (isPublishing) return@withContext

                    if (exists && deepLink.isNotEmpty()) {
                        roomMissCount = 0
                        currentRoomInfo = NeriDeepLinkHelper.parseInvitation(deepLink)?.copy(
                            currentSong = currentSong,
                            currentCover = currentCover
                        )
                        updateRoomUi(
                            exists = true,
                            inviter = inviter,
                            publisher = publisher,
                            hostAvatarUrl = hostAvatarUrl,
                            deepLink = deepLink,
                            currentSong = currentSong,
                            currentCover = currentCover,
                            durationMs = durationMs,
                            basePositionMs = basePositionMs,
                            baseTimestampMs = baseTimestampMs,
                            playbackRate = playbackRate,
                            isPlaying = isPlaying
                        )
                    } else {
                        roomMissCount++
                        if (roomMissCount >= ROOM_MISS_THRESHOLD) {
                            currentRoomInfo = null
                            if (isHosting) {
                                isHosting = false
                                stopHeartbeat()
                            }
                            updateRoomUi(false, null, null, null, null, null, null)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "轮询网络波动: ${e.message}")
        }
    }

    private fun postRoomState(info: RoomInfo?, action: String, isResume: Boolean = false) {
        val effectiveInviter = if (!info?.inviter.isNullOrBlank()) info.inviter else sessionManager.getUsername()

        val json = JSONObject().apply {
            put("action", action)
            put("username", sessionManager.getUsername())
            if (isResume) put("resume", true)
            if (info != null) {
                put("roomId", info.roomId)
                put("inviter", effectiveInviter)
                put("publisher", sessionManager.getUsername())
                put("secret", info.secret)
                put("deepLink", info.rawUri)
                put("serverUrl", info.serverUrl ?: DEFAULT_NERI_SERVER)
            }
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$BASE_URL/room/broadcast").post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "上报状态网络异常: $action", e)
                runOnUiThread {
                    if (action == "start") {
                        isPublishing = false
                        btnPublishRoom.isEnabled = true
                        btnPublishRoom.text = "开启"
                        btnPasteInvite.isEnabled = true
                        etInviteCode.isEnabled = true
                        showTip("网络连接超时，开启失败")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string().orEmpty()
                val isOk = response.isSuccessful

                runOnUiThread {
                    if (action == "start") {
                        isPublishing = false
                        btnPublishRoom.isEnabled = true
                        btnPublishRoom.text = "开启"
                        btnPasteInvite.isEnabled = true
                        etInviteCode.isEnabled = true

                        if (!isOk) {
                            var errMsg = "开启失败(${response.code})"
                            try {
                                val errJson = JSONObject(respBody)
                                errMsg = errJson.optString("message", errMsg)
                            } catch (_: Exception) {}

                            if (response.code == 400 || response.code == 409) {
                                sessionManager.clearHostingRoom()
                                isHosting = false
                                stopHeartbeat()
                                if (isResume) {
                                    updateRoomUi(false, null, null, null, null, null, null)
                                }
                            }
                            showTip(errMsg)
                        } else {
                            etInviteCode.setText("")
                            isHosting = true

                            var returnedSong: String? = null
                            var returnedCover: String? = null
                            var durationMs = 0L
                            var basePositionMs = 0L
                            var baseTimestampMs = System.currentTimeMillis()
                            var playbackRate = 1.0
                            var isPlaying = true

                            try {
                                val resData = JSONObject(respBody).optJSONObject("data")
                                returnedSong = resData?.optString("currentSong", "")?.ifEmpty { null }
                                returnedCover = resData?.optString("currentCover", "")?.ifEmpty { null }
                                durationMs = resData?.optLong("durationMs", 0L) ?: 0L
                                basePositionMs = resData?.optLong("basePositionMs", 0L) ?: 0L
                                baseTimestampMs = resData?.optLong("baseTimestampMs", System.currentTimeMillis()) ?: System.currentTimeMillis()
                                playbackRate = resData?.optDouble("playbackRate", 1.0) ?: 1.0
                                isPlaying = resData?.optBoolean("isPlaying", true) ?: true
                            } catch (_: Exception) {}

                            val updatedInfo = info?.copy(
                                currentSong = returnedSong,
                                currentCover = returnedCover
                            )
                            currentRoomInfo = updatedInfo
                            roomMissCount = 0
                            hostRetryCount = 0

                            updatedInfo?.let { persistHostingRoom(it) }

                            val myUsername = sessionManager.getUsername()
                            updateRoomUi(
                                exists = true,
                                inviter = effectiveInviter,
                                publisher = myUsername,
                                hostAvatarUrl = sessionManager.getAvatarUri(),
                                deepLink = info?.rawUri,
                                currentSong = returnedSong,
                                currentCover = returnedCover,
                                durationMs = durationMs,
                                basePositionMs = basePositionMs,
                                baseTimestampMs = baseTimestampMs,
                                playbackRate = playbackRate,
                                isPlaying = isPlaying
                            )

                            startHeartbeat()

                            if (!isResume) {
                                showTip("房间上线成功！")
                            }
                        }
                    }
                }
            }
        })
    }
}