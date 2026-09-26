package com.example.social_music

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
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
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_URL = "https://www.white667.xyz/api"
        private const val DEFAULT_NERI_SERVER = "https://neriplayer.hancat.work"

        private const val ANIM_NONE = 0
        private const val ANIM_SPINNER = 1
        private const val ANIM_HEX = 2

        // 「房间消失」防抖：连续这么多次查不到才切「空闲」，避免一次网络抖动把 UI 打成空闲
        private const val ROOM_MISS_THRESHOLD = 3

        // 房主心跳收到 404 后自动重开播的最大次数
        private const val MAX_HOST_RETRY = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private lateinit var sessionManager: SessionManager
    private var currentDeepLink: String? = null
    private var currentRoomInfo: RoomInfo? = null
    private var isHosting = false
    private var currentAnimType = ANIM_NONE

    // 并发防跳动锁
    private var isPublishing = false

    // 连续查不到房间的次数（防抖用，只有连续 ROOM_MISS_THRESHOLD 次才真的切「空闲」）
    private var roomMissCount = 0

    // 房主心跳 404 后的自动重开播次数
    private var hostRetryCount = 0

    // 协程任务引用
    private var initProbeJob: Job? = null
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null

    // 悬浮胶囊 Tip 引用
    private var capsuleTipView: View? = null
    private var capsuleHideRunnable: Runnable? = null

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

        initViews()
        initWebViewSettings()
        setupListeners()

        // 核心改动：视图初始化后的第 1 毫秒立刻锁定加载态，把默认 XML 中的“空闲”与口令区彻底隐藏
        showLoadingState()

        // 息屏/后台被系统回收后重新进入：先把「我正在放歌」的身份认领回来
        restoreHostingStateIfAny()
    }

    override fun onResume() {
        super.onResume()
        updateUserUi()

        // 进入或唤醒页面时，立刻展示纯净加载屏并探测最新状态
        showLoadingState()
        checkAndProbeRoomOnEntry()

        if (layoutMembersTabContent.visibility == View.VISIBLE) {
            fetchRegisteredMembers()
        }
    }

    override fun onStop() {
        super.onStop()
        initProbeJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
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

    // =========================================================================
    // 极速顶部灵动药丸胶囊提示（0延迟、零排队）
    // =========================================================================
    private fun showTip(message: String) {
        val decorView = window.decorView as? ViewGroup ?: return
        capsuleHideRunnable?.let { decorView.removeCallbacks(it) }

        if (capsuleTipView == null) {
            val pill = FrameLayout(this).apply {
                elevation = 18f
                setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp2px(99).toFloat()
                    setColor(0xEE0F172A.toInt())
                    setStroke(dp2px(1), 0x3394A3B8.toInt())
                }
            }

            val tv = TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFF8FAFC.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            pill.addView(tv)

            val statusBarHeight = getStatusBarHeight()
            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = statusBarHeight + dp2px(12)
            }

            decorView.addView(pill, layoutParams)
            capsuleTipView = pill
        }

        val pill = capsuleTipView as? FrameLayout ?: return
        val textView = pill.getChildAt(0) as? TextView ?: return

        textView.text = message
        pill.visibility = View.VISIBLE

        pill.scaleX = 0.85f
        pill.scaleY = 0.85f
        pill.alpha = 0f
        pill.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val hideTask = Runnable {
            pill.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .alpha(0f)
                .setDuration(120)
                .withEndAction { pill.visibility = View.GONE }
                .start()
        }
        capsuleHideRunnable = hideTask
        decorView.postDelayed(hideTask, 1500)
    }

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun getStatusBarHeight(): Int {
        var result = dp2px(24)
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
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
            ANIM_SPINNER -> getDotSpinnerHtml()
            ANIM_HEX -> getHexLoaderHtml()
            else -> ""
        }
        webHexLoader.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }

    // 纯净加载态
    private fun showLoadingState() {
        switchAnimation(ANIM_SPINNER)

        // 下方文字删掉
        tvHostMessage.visibility = View.GONE
        tvHostMessage.text = ""

        ivHostAvatar.visibility = View.GONE
        btnJoin.visibility = View.GONE
        layoutHostSection.visibility = View.GONE
    }

    private fun showMembersLoading() {
        tvMemberCountBadge.visibility = View.GONE
        layoutMembersContainer.removeAllViews()

        val loader = getOrCreateMembersLoader()
        (loader.parent as? ViewGroup)?.removeView(loader)
        loader.loadDataWithBaseURL(null, getDotSpinnerHtml(), "text/html", "UTF-8", null)

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
                showTip("当前无可用链接")
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
                showTip("非法 NeriPlayer 邀请口令")
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
        btnJoin.text = "核验"

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
                                showTip("未找到 NeriPlayer")
                            }
                        } else {
                            showTip("房主已结束放歌")
                            updateRoomUi(false, null, null, null, null)
                        }
                    } else {
                        showTip("核验失败")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "核验请求异常", e)
                withContext(Dispatchers.Main) {
                    btnJoin.isEnabled = true
                    btnJoin.text = "加入"
                    showTip("网络异常")
                }
            }
        }
    }

    // =========================================================================
    // 核心业务：拉取并渲染全体密钥注册成员
    // =========================================================================
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
                } else {
                    Log.w(TAG, "成员列表请求未成功: code=${response.code}, body=$body")
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

            tvMeBadge.visibility = if (sessionManager.isLoggedIn() && member.username == myUsername) {
                View.VISIBLE
            } else {
                View.GONE
            }

            tvHostingBadge.visibility = if (member.isHosting) {
                View.VISIBLE
            } else {
                View.GONE
            }

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

    data class RegisteredMember(
        val username: String,
        val avatarUrl: String,
        val isHosting: Boolean
    )

    // =========================================================================
    // 房主身份持久化（息屏 / 被系统回收后还能认领自己的房间）
    // =========================================================================
    private fun persistHostingRoom(info: RoomInfo) {
        val json = JSONObject().apply {
            put("owner", sessionManager.getUsername())
            put("roomId", info.roomId ?: "")
            put("secret", info.secret ?: "")
            put("deepLink", info.rawUri)
            put("serverUrl", info.serverUrl ?: DEFAULT_NERI_SERVER)
            put("inviter", info.inviter ?: "")
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
                serverUrl = json.optString("serverUrl", "").ifEmpty { null }
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

    // =========================================================================
    // 房间状态与轮询
    // =========================================================================
    private fun checkAndProbeRoomOnEntry() {
        initProbeJob?.cancel()
        roomMissCount = 0
        initProbeJob = lifecycleScope.launch(Dispatchers.IO) {
            val targetUrl = if (isHosting) {
                "$BASE_URL/room/status?force=true"
            } else {
                "$BASE_URL/room/status"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .get()
                .build()

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

                    withContext(Dispatchers.Main) {
                        if (exists && deepLink.isNotEmpty()) {
                            val info = NeriDeepLinkHelper.parseInvitation(deepLink)
                            currentRoomInfo = info

                            val myUsername = sessionManager.getUsername()
                            val isMe = sessionManager.isLoggedIn() && (myUsername == publisher || myUsername == inviter)

                            if (isMe && info != null) {
                                isHosting = true
                                startHeartbeat()
                            }
                            updateRoomUi(true, inviter, publisher, hostAvatarUrl, deepLink)
                        } else {
                            val info = currentRoomInfo ?: restoreHostingRoom()
                            if (info != null) {
                                Log.w(TAG, "服务端房间已失效，自动重新开播 roomId=${info.roomId}")
                                currentRoomInfo = info
                                isHosting = true
                                startHeartbeat()
                                // 保持纯净加载屏，后台重开播，直到开播成功再展示放歌 UI
                                postRoomState(info, action = "start", isResume = true)
                            } else {
                                isHosting = false
                                stopHeartbeat()
                                updateRoomUi(false, null, null, null, null)
                            }
                        }
                        startPollingRoomStatus()
                    }
                } else {
                    Log.w(TAG, "进入房间探测未成功: code=${response.code}")
                    withContext(Dispatchers.Main) {
                        if (!isHosting) {
                            updateRoomUi(false, null, null, null, null)
                        } else {
                            // 房主端：若探测请求未成功，兜底恢复放歌 UI，避免死锁在加载态
                            val myUsername = sessionManager.getUsername()
                            updateRoomUi(
                                exists = true,
                                inviter = currentRoomInfo?.inviter ?: myUsername,
                                publisher = myUsername,
                                hostAvatarUrl = sessionManager.getAvatarUri(),
                                deepLink = currentRoomInfo?.rawUri
                            )
                        }
                        startPollingRoomStatus()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "探测异常: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!isHosting) {
                        updateRoomUi(false, null, null, null, null)
                    } else {
                        val myUsername = sessionManager.getUsername()
                        updateRoomUi(
                            exists = true,
                            inviter = currentRoomInfo?.inviter ?: myUsername,
                            publisher = myUsername,
                            hostAvatarUrl = sessionManager.getAvatarUri(),
                            deepLink = currentRoomInfo?.rawUri
                        )
                    }
                    startPollingRoomStatus()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateRoomUi(
        exists: Boolean,
        inviter: String?,
        publisher: String?,
        hostAvatarUrl: String?,
        deepLink: String?
    ) {
        if (!exists && isPublishing) return

        switchAnimation(ANIM_HEX)

        // 探测完成，恢复文字展示
        tvHostMessage.visibility = View.VISIBLE

        if (!exists) {
            currentDeepLink = null
            currentRoomInfo = null
            isHosting = false
            stopHeartbeat()

            tvHostMessage.text = "空闲"
            ivHostAvatar.visibility = View.GONE
            btnJoin.visibility = View.GONE
            layoutHostSection.visibility = View.VISIBLE
        } else {
            currentDeepLink = deepLink

            val myUsername = sessionManager.getUsername()
            val isMe = sessionManager.isLoggedIn() && (myUsername == publisher || myUsername == inviter)

            tvHostMessage.text = if (isMe) "你正在放歌" else "${inviter ?: "群友"} 正在放歌"

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
        btnPublishRoom.text = "核验开启中..."
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
        val request = Request.Builder()
            .url("$BASE_URL/room/broadcast")
            .post(requestBody)
            .build()

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
            updateRoomUi(false, null, null, null, null)
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

        val request = Request.Builder()
            .url("$BASE_URL/room/status")
            .get()
            .build()

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

                withContext(Dispatchers.Main) {
                    if (isPublishing) return@withContext

                    if (exists && deepLink.isNotEmpty()) {
                        roomMissCount = 0
                        currentRoomInfo = NeriDeepLinkHelper.parseInvitation(deepLink)
                        updateRoomUi(true, inviter, publisher, hostAvatarUrl, deepLink)
                    } else {
                        roomMissCount++
                        if (roomMissCount >= ROOM_MISS_THRESHOLD) {
                            currentRoomInfo = null
                            if (isHosting) {
                                isHosting = false
                                stopHeartbeat()
                            }
                            updateRoomUi(false, null, null, null, null)
                        }
                    }
                }
            } else {
                Log.w(TAG, "查询未成功: code=${response.code}")
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
        val request = Request.Builder()
            .url("$BASE_URL/room/broadcast")
            .post(requestBody)
            .build()

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
                        showTip("网络连接超时")
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
                                    updateRoomUi(false, null, null, null, null)
                                }
                            }
                            showTip(errMsg)
                        } else {
                            etInviteCode.setText("")
                            isHosting = true
                            currentRoomInfo = info
                            roomMissCount = 0
                            hostRetryCount = 0

                            info?.let { persistHostingRoom(it) }

                            val myUsername = sessionManager.getUsername()
                            updateRoomUi(
                                exists = true,
                                inviter = effectiveInviter,
                                publisher = myUsername,
                                hostAvatarUrl = sessionManager.getAvatarUri(),
                                deepLink = info?.rawUri
                            )

                            startHeartbeat()

                            // 手动开启时提示上线成功
                            if (!isResume) {
                                showTip("成功")
                            }
                        }
                    }
                }
            }
        })
    }

    private fun getDotSpinnerHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
        <style>
          * { box-sizing: border-box; }
          body {
            margin: 0; padding: 0; background: transparent; overflow: hidden;
            display: flex; justify-content: center; align-items: center; height: 100vh;
          }
          .spinner {
            position: relative;
            width: 60px;
            height: 60px;
            display: flex;
            justify-content: center;
            align-items: center;
            border-radius: 50%;
            margin-left: -75px;
          }

          .spinner span {
            position: absolute;
            top: 50%;
            left: var(--left);
            width: 35px;
            height: 7px;
            background: #ffff;
            animation: dominos 1s ease infinite;
            box-shadow: 2px 2px 3px 0px black;
          }

          .spinner span:nth-child(1) {
            --left: 80px;
            animation-delay: 0.125s;
          }

          .spinner span:nth-child(2) {
            --left: 70px;
            animation-delay: 0.3s;
          }

          .spinner span:nth-child(3) {
            left: 60px;
            animation-delay: 0.425s;
          }

          .spinner span:nth-child(4) {
            animation-delay: 0.54s;
            left: 50px;
          }

          .spinner span:nth-child(5) {
            animation-delay: 0.665s;
            left: 40px;
          }

          .spinner span:nth-child(6) {
            animation-delay: 0.79s;
            left: 30px;
          }

          .spinner span:nth-child(7) {
            animation-delay: 0.915s;
            left: 20px;
          }

          .spinner span:nth-child(8) {
            left: 10px;
          }

          @keyframes dominos {
            50% {
              opacity: 0.7;
            }

            75% {
              -webkit-transform: rotate(90deg);
              transform: rotate(90deg);
            }

            80% {
              opacity: 1;
            }
          }
        </style>
        </head>
        <body>
          <div class="spinner">
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
            <span></span>
          </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun getHexLoaderHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
        <style>
          * { box-sizing: border-box; }
          body {
            margin: 0; padding: 0; background: transparent; overflow: hidden;
            display: flex; justify-content: center; align-items: center; height: 100vh;
          }
          .socket {
            width: 200px; height: 200px; position: relative;
            transform: scale(0.62);
          }
          .hex-brick {
            background: #0F172A; width: 30px; height: 17px; position: absolute; top: 5px;
            animation: fade00 2s infinite; -webkit-animation: fade00 2s infinite;
          }
          .h2 { transform: rotate(60deg); -webkit-transform: rotate(60deg); }
          .h3 { transform: rotate(-60deg); -webkit-transform: rotate(-60deg); }
          .gel { height: 30px; width: 30px; position: absolute; top: 50%; left: 50%; }
          .center-gel {
            margin-left: -15px; margin-top: -15px;
            animation: pulse00 2s infinite; -webkit-animation: pulse00 2s infinite;
          }
          .c1 { margin-left: -47px; margin-top: -15px; }
          .c2 { margin-left: -31px; margin-top: -43px; }
          .c3 { margin-left: 1px; margin-top: -43px; }
          .c4 { margin-left: 17px; margin-top: -15px; }
          .c5 { margin-left: -31px; margin-top: 13px; }
          .c6 { margin-left: 1px; margin-top: 13px; }
          .c7 { margin-left: -63px; margin-top: -43px; }
          .c8 { margin-left: 33px; margin-top: -43px; }
          .c9 { margin-left: -15px; margin-top: 41px; }
          .c10 { margin-left: -63px; margin-top: 13px; }
          .c11 { margin-left: 33px; margin-top: 13px; }
          .c12 { margin-left: -15px; margin-top: -71px; }
          .c13 { margin-left: -47px; margin-top: -71px; }
          .c14 { margin-left: 17px; margin-top: -71px; }
          .c15 { margin-left: -47px; margin-top: 41px; }
          .c16 { margin-left: 17px; margin-top: 41px; }
          .c17 { margin-left: -79px; margin-top: -15px; }
          .c18 { margin-left: 49px; margin-top: -15px; }
          .c19 { margin-left: -63px; margin-top: -99px; }
          .c20 { margin-left: 33px; margin-top: -99px; }
          .c21 { margin-left: 1px; margin-top: -99px; }
          .c22 { margin-left: -31px; margin-top: -99px; }
          .c23 { margin-left: -63px; margin-top: 69px; }
          .c24 { margin-left: 33px; margin-top: 69px; }
          .c25 { margin-left: 1px; margin-top: 69px; }
          .c26 { margin-left: -31px; margin-top: 69px; }
          .c27 { margin-left: -79px; margin-top: -15px; }
          .c28 { margin-left: -95px; margin-top: -43px; }
          .c29 { margin-left: -95px; margin-top: 13px; }
          .c30 { margin-left: 49px; margin-top: 41px; }
          .c31 { margin-left: -79px; margin-top: -71px; }
          .c32 { margin-left: -111px; margin-top: -15px; }
          .c33 { margin-left: 65px; margin-top: -43px; }
          .c34 { margin-left: 65px; margin-top: 13px; }
          .c35 { margin-left: -79px; margin-top: 41px; }
          .c36 { margin-left: 49px; margin-top: -71px; }
          .c37 { margin-left: 81px; margin-top: -15px; }

          .r1 { animation: pulse00 2s infinite .2s; }
          .r2 { animation: pulse00 2s infinite .4s; }
          .r3 { animation: pulse00 2s infinite .6s; }
          .r1 > .hex-brick { animation: fade00 2s infinite .2s; }
          .r2 > .hex-brick { animation: fade00 2s infinite .4s; }
          .r3 > .hex-brick { animation: fade00 2s infinite .6s; }

          @keyframes pulse00 {
            0% { transform: scale(1); }
            50% { transform: scale(0.01); }
            100% { transform: scale(1); }
          }
          @keyframes fade00 {
            0% { background: #334155; }
            50% { background: #0F172A; }
            100% { background: #64748B; }
          }
        </style>
        </head>
        <body>
          <div class="socket">
            <div class="gel center-gel"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c1 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c2 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c3 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c4 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c5 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c6 r1"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c7 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c8 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c9 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c10 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c11 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c12 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c13 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c14 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c15 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c16 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c17 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c18 r2"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c19 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c20 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c21 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c22 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c23 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c24 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c25 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c26 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c27 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c28 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c29 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c30 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c31 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c32 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c33 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c34 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c35 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c36 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
            <div class="gel c37 r3"><div class="hex-brick h1"></div><div class="hex-brick h2"></div><div class="hex-brick h3"></div></div>
          </div>
        </body>
        </html>
        """.trimIndent()
    }
}

// =========================================================================
// 核心实体与工具类
// =========================================================================
data class RoomInfo(
    val rawUri: String,
    val roomId: String?,
    val inviter: String?,
    val secret: String?,
    val serverUrl: String?
)

object NeriDeepLinkHelper {
    private val SCHEME_REGEX = Pattern.compile("neriplayer://[\\w\\-./?%&=:#@+~]+")

    fun parseInvitation(text: String): RoomInfo? {
        val matcher = SCHEME_REGEX.matcher(text)
        if (!matcher.find()) return null

        val uriString = matcher.group() ?: return null
        return try {
            val uri = uriString.toUri()
            RoomInfo(
                rawUri = uriString,
                roomId = uri.getQueryParameter("roomId"),
                inviter = uri.getQueryParameter("inviter"),
                secret = uri.getQueryParameter("secret"),
                serverUrl = uri.getQueryParameter("server") ?: uri.getQueryParameter("baseUrl")
            )
        } catch (e: Exception) {
            Log.e("DeepLinkHelper", "URI 解析异常", e)
            null
        }
    }

    fun launchPlayer(context: Context, deepLink: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, deepLink.toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("DeepLinkHelper", "未安装 NeriPlayer", e)
            false
        }
    }
}

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_session_pref", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_AVATAR_URI = "auth_avatar_uri"
        private const val KEY_HOSTING_ROOM = "hosting_room_json"
    }

    fun saveAuthToken(token: String, username: String) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putString(KEY_USERNAME, username)
        }
    }

    fun saveUsername(username: String) {
        prefs.edit { putString(KEY_USERNAME, username) }
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "未登录") ?: "未登录"
    }

    fun saveAvatarUri(uri: String) {
        prefs.edit { putString(KEY_AVATAR_URI, uri) }
    }

    fun getAvatarUri(): String? {
        return prefs.getString(KEY_AVATAR_URI, null)
    }

    fun isLoggedIn(): Boolean {
        return !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()
    }

    // ===== 房主身份持久化 =====
    fun saveHostingRoom(json: String) {
        prefs.edit { putString(KEY_HOSTING_ROOM, json) }
    }

    fun getHostingRoom(): String? {
        return prefs.getString(KEY_HOSTING_ROOM, null)
    }

    fun clearHostingRoom() {
        prefs.edit { remove(KEY_HOSTING_ROOM) }
    }

    fun clearSession() {
        prefs.edit { clear() }
    }

    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }
}