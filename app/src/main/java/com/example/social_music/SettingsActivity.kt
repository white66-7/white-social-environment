package com.example.social_music

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.example.social_music.net.RoomApiService
import com.example.social_music.utils.CapsuleTipManager
import com.example.social_music.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var tipManager: CapsuleTipManager

    private lateinit var tvUsername: TextView
    private lateinit var ivAvatar: ShapeableImageView
    private val api = RoomApiService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sessionManager = SessionManager(this)
        tipManager = CapsuleTipManager(this)

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

    private fun showTip(message: String) = tipManager.showTip(message)

    private fun dp2px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

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
        // 身份一律由 token 决定。旧版是从头像 URL 里正则抠出 QQ 号再发给服务端，
        // 服务端又完全信任这个 QQ —— 等于任何人都能改别人的昵称。
        val token = sessionManager.getToken()
        if (token.isNullOrEmpty()) {
            showTip("登录状态已失效，请重新登录")
            return
        }

        lifecycleScope.launch {
            val (isOk, errorMessage) = api.updateUsername(token, newName)
            if (isOk) {
                tvUsername.text = newName
                sessionManager.saveUsername(newName)
                showTip("昵称修改为：$newName")
            } else {
                showTip(errorMessage ?: "保存失败")
            }
        }
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