package com.example.social_music

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.social_music.net.ApiConfig
import com.example.social_music.net.Http
import com.example.social_music.utils.CapsuleTipManager
import com.example.social_music.utils.SessionManager
import com.google.android.material.button.MaterialButton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var tipManager: CapsuleTipManager

    private lateinit var etQqNumber: EditText
    private lateinit var pin1: EditText
    private lateinit var pin2: EditText
    private lateinit var pin3: EditText
    private lateinit var pin4: EditText
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionManager = SessionManager(this)
        tipManager = CapsuleTipManager(this)

        initViews()
        setupPinJumpLogic()
    }

    private fun showTip(message: String) = tipManager.showTip(message)

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
            .url("${ApiConfig.BASE_URL}/auth/login")
            .post(requestBody)
            .build()

        Http.client.newCall(request).enqueue(object : Callback {
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
                            // qq 才是稳定身份，昵称随时可改，比对成员/房主都要用它
                            sessionManager.saveQq(userObj?.optString("qq", "").orEmpty())

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