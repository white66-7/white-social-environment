package com.example.social_music.utils

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

class CapsuleTipManager(private val activity: Activity) {

    private var capsuleTipView: View? = null
    private var capsuleHideRunnable: Runnable? = null

    fun showTip(message: String) {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        capsuleHideRunnable?.let { decorView.removeCallbacks(it) }

        if (capsuleTipView == null) {
            val pill = FrameLayout(activity).apply {
                elevation = 18f
                setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp2px(99).toFloat()
                    setColor(0xEE0F172A.toInt())
                    setStroke(dp2px(1), 0x3394A3B8.toInt())
                }
            }

            val tv = TextView(activity).apply {
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

    private fun dp2px(dp: Int): Int = (dp * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun getStatusBarHeight(): Int {
        var result = dp2px(24)
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = activity.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }
}