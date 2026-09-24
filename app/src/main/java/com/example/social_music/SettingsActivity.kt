package com.example.social_music

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var tvUsername: TextView
    private lateinit var ivAvatar: ShapeableImageView
    private val client = OkHttpClient()

    // 顶部灵动药丸胶囊引用（与 MainActivity 一致）
    private var capsuleTipView: View? = null
    private var capsuleHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sessionManager = SessionManager(this)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val itemAvatar = findViewById<LinearLayout>(R.id.itemAvatar)
        val itemUsername = findViewById<LinearLayout>(R.id.itemUsername)
        val itemLogout = findViewById<LinearLayout>(R.id.itemLogout)
        tvUsername = findViewById(R.id.tvSettingsUsername)
        ivAvatar = findViewById(R.id.ivSettingsAvatar)

        btnBack.setOnClickListener { finish() }

        // 头像提示
        itemAvatar.setOnClickListener {
            showTip("头像同步QQ")
        }

        // 点击昵称：弹出与应用同款风格的自定义弹窗
        itemUsername.setOnClickListener {
            showEditUsernameDialog()
        }

        // 退出登录弹窗
        itemLogout.setOnClickListener {
            showLogoutConfirmDialog()
        }

        // 回显 QQ 头像与名称
        tvUsername.text = sessionManager.getUsername()
        val avatarUrl = sessionManager.getAvatarUri()
        if (!avatarUrl.isNullOrEmpty()) {
            ivAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_gray)
                error(R.drawable.bg_avatar_gray)
                transformations(CircleCropTransformation())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val decorView = window.decorView as? ViewGroup
        capsuleHideRunnable?.let { decorView?.removeCallbacks(it) }
    }

    // =========================================================================
    // 极速灵动药丸胶囊提示（完全同步 MainActivity 样式）
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
                    setColor(0xEE0F172A.toInt()) // 深石板蓝半透背景
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

    // =========================================================================
    // MainActivity 风格的高质感无边框圆角输入弹窗
    // =========================================================================
    private fun showEditUsernameDialog() {
        val currentName = tvUsername.text.toString()

        // 1. 卡片根布局（圆角矩形 + 质感白底）
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(22), dp2px(24), dp2px(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(20).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
        }

        // 2. 标题（底部预留 16dp 间距直接连接输入框）
        val tvTitle = TextView(this).apply {
            text = "修改昵称"
            textSize = 17f
            setTextColor(0xFF0F172A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp2px(16))
        }
        rootLayout.addView(tvTitle)

        // 3. 输入框（微灰底色 + 浅边框 + 优雅圆角）
        val etInput = EditText(this).apply {
            hint = "输入新昵称"
            setHintTextColor(0xFF94A3B8.toInt())
            setText(currentName)
            textSize = 14f
            setTextColor(0xFF0F172A.toInt())
            filters = arrayOf(InputFilter.LengthFilter(12))
            isSingleLine = true
            setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(12).toFloat()
                setColor(0xFFF8FAFC.toInt())
                setStroke(dp2px(1), 0xFFE2E8F0.toInt())
            }
            setSelection(text.length)
        }
        rootLayout.addView(etInput)

        // 4. 底部按钮行（取消 + 保存）
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp2px(20), 0, 0)
        }

        val btnCancel = MaterialButton(this).apply {
            text = "取消"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF64748B.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFF1F5F9.toInt())
            cornerRadius = dp2px(12)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, dp2px(44), 1f).apply {
                marginEnd = dp2px(10)
            }
        }

        val btnConfirm = MaterialButton(this).apply {
            text = "保存"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFF0F172A.toInt()) // 主色深蓝黑
            cornerRadius = dp2px(12)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, dp2px(44), 1f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)
        rootLayout.addView(btnRow)

        // 5. 创建弹窗并设置完全透明背景窗口
        val dialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val newName = etInput.text.toString().trim()
            if (newName.isEmpty()) {
                showTip("昵称不能为空")
                return@setOnClickListener
            }
            if (newName == currentName) {
                dialog.dismiss()
                return@setOnClickListener
            }

            dialog.dismiss()
            updateUsernameToServer(newName)
        }

        dialog.show()
    }

    // =========================================================================
    // 同步到后端并在成功后弹出胶囊提示
    // =========================================================================
    private fun updateUsernameToServer(newName: String) {
        val avatarUrl = sessionManager.getAvatarUri().orEmpty()
        val qqNumber = if (avatarUrl.contains("nk=")) {
            avatarUrl.substringAfter("nk=").substringBefore("&")
        } else {
            ""
        }
        val token = sessionManager.getToken().orEmpty()

        val json = JSONObject().apply {
            put("qq", qqNumber)
            put("token", token)
            put("newUsername", newName)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://www.white667.xyz/api/user/update-name")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("SettingsActivity", "改名请求失败", e)
                runOnUiThread {
                    showTip("网络异常: ${e.localizedMessage ?: "连接失败"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                android.util.Log.d("SettingsActivity", "改名响应: ${response.code} $body")

                runOnUiThread {
                    if (response.isSuccessful) {
                        tvUsername.text = newName
                        sessionManager.saveUsername(newName)
                        showTip("昵称修改为：$newName")
                    } else {
                        var errMsg = "保存失败"
                        try {
                            errMsg = JSONObject(body).optString("message", errMsg)
                        } catch (_: Exception) {}
                        showTip(errMsg)
                    }
                }
            }
        })
    }

    private fun showLogoutConfirmDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_logout, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnDialogConfirm)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            dialog.dismiss()
            sessionManager.clearSession()
            showTip("退出登录")
            finish()
        }

        dialog.show()
    }
}