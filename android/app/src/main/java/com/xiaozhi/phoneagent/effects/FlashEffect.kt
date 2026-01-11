package com.xiaozhi.phoneagent.effects

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class FlashEffect(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = View(context)
    private var attached = false

    fun trigger(color: Int, durationMs: Long) {
        if (!canDraw()) return
        view.background = buildBorderDrawable(color)
        if (!attached) {
            windowManager.addView(view, createLayoutParams())
            attached = true
        }
        view.postDelayed({
            if (attached) {
                windowManager.removeView(view)
                attached = false
            }
        }, durationMs)
    }

    private fun canDraw(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun buildBorderDrawable(color: Int): GradientDrawable {
        val strokeWidth = dpToPx(6)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
