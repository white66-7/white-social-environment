package com.example.social_music

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // 15 秒长稳态客户端，抵御冷启动
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var etQqNumber: EditText
    private lateinit var pin1: EditText
    private lateinit var pin2: EditText
    private lateinit var pin3: EditText
    private lateinit var pin4: EditText
    private lateinit var btnLogin: MaterialButton

    // 顶部灵动药丸胶囊引用（与 MainActivity / SettingsActivity 完全一致）
    private var capsuleTipView: View? = null
    private var capsuleHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)

        initViews()
        setupPinJumpLogic()
    }

    override fun onDestroy() {
        super.onDestroy()
        val decorView = window.decorView as? ViewGroup
        capsuleHideRunnable?.let { decorView?.removeCallbacks(it) }
    }

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
                    setColor(0xEE0F172A.toInt()) // 深石板蓝半透明底色
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
        val btnClose = findViewById<ImageView>(R.id.btnClose)
        etQqNumber = findViewById(R.id.etUsername)
        etQqNumber.hint = "请输入您的 QQ 号"
        etQqNumber.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        pin1 = findViewById(R.id.pin1)
        pin2 = findViewById(R.id.pin2)
        pin3 = findViewById(R.id.pin3)
        pin4 = findViewById(R.id.pin4)
        btnLogin = findViewById(R.id.btnLogin)

        btnClose.setOnClickListener { finish() }

        btnLogin.setOnClickListener {
            performLogin()
        }
    }

    private fun setupPinJumpLogic() {
        val pinList = listOf(pin1, pin2, pin3, pin4)

        for (i in pinList.indices) {
            val current = pinList[i]

            current.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < pinList.size - 1) {
                        pinList[i + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            current.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                    if (current.text.isEmpty() && i > 0) {
                        pinList[i - 1].apply {
                            requestFocus()
                            setText("")
                        }
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    // =========================================================================
    // 认证登录请求
    // =========================================================================
    private fun performLogin() {
        val qqNumber = etQqNumber.text.toString().trim()
        val pin = "${pin1.text}${pin2.text}${pin3.text}${pin4.text}".trim()

        if (qqNumber.isEmpty()) {
            showTip("请输入您的 QQ 号码")
            etQqNumber.requestFocus()
            return
        }

        if (pin.length < 4) {
            showTip("请设置 4 位专属口令")
            return
        }

        btnLogin.isEnabled = false
        btnLogin.text = "认证中"

        val json = JSONObject().apply {
            put("qq", qqNumber)
            put("pin", pin)
        }
        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://www.white667.xyz/api/auth/login")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    btnLogin.isEnabled = true
                    btnLogin.text = "认证进入"
                    showTip("网络异常: ${e.localizedMessage ?: "连接失败"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                runOnUiThread {
                    btnLogin.isEnabled = true
                    btnLogin.text = "认证进入"

                    if (response.isSuccessful && body.isNotEmpty()) {
                        try {
                            val jsonResp = JSONObject(body)
                            val token = jsonResp.optString("token", "")
                            val userObj = jsonResp.optJSONObject("user")
                            val nickname = userObj?.optString("username", "QQ用户") ?: "QQ用户"
                            val avatarUrl = userObj?.optString("avatarUrl", "") ?: ""

                            sessionManager.saveAuthToken(token, nickname)
                            sessionManager.saveAvatarUri(avatarUrl)

                            showTip("认证成功: $nickname")
                            // 延时 400ms 退出，确保用户能看清药丸提示
                            window.decorView.postDelayed({ finish() }, 400)
                        } catch (e: Exception) {
                            showTip("数据解析异常")
                        }
                    } else {
                        var errMsg = "口令校验失败"
                        try {
                            val errJson = JSONObject(body)
                            errMsg = errJson.optString("message", errMsg)
                        } catch (_: Exception) {}

                        clearPinInputs()
                        showTip(errMsg)
                    }
                }
            }
        })
    }

    private fun clearPinInputs() {
        pin1.setText("")
        pin2.setText("")
        pin3.setText("")
        pin4.setText("")
        pin1.requestFocus()
    }
}