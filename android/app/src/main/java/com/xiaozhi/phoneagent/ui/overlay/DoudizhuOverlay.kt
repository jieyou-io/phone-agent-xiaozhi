package com.xiaozhi.phoneagent.ui.overlay

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class DoudizhuOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#CC1E3A5F"))
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(24, 16, 24, 16)
    }
    private var attached = false

    fun showSuggestion(text: String) {
        if (!canDraw()) return
        view.text = text
        if (!attached) {
            windowManager.addView(view, createLayoutParams())
            attached = true
        }
    }

    fun hide() {
        if (attached) {
            windowManager.removeView(view)
            attached = false
        }
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 160
        }
    }
}
