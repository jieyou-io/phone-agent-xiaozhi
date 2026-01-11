package com.xiaozhi.phoneagent.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class CompositionOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = View(context).apply {
        background = buildBorderDrawable()
    }
    private var attached = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private val displayMetrics = DisplayMetrics().apply {
        windowManager.defaultDisplay.getRealMetrics(this)
    }

    fun show(region: String, direction: String) {
        if (!canDraw()) return
        val resolvedRegion = if (region.isBlank() || region == "auto") "center" else region
        view.contentDescription = "region:$resolvedRegion direction:$direction"
        if (!attached) {
            layoutParams = createLayoutParams(resolvedRegion)
            windowManager.addView(view, layoutParams)
            attached = true
        } else {
            layoutParams = createLayoutParams(resolvedRegion)
            windowManager.updateViewLayout(view, layoutParams)
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

    private fun createLayoutParams(region: String): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val size = computeBox(region)
        val params = WindowManager.LayoutParams(
            size.first,
            size.second,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        val position = computePosition(region, size.first, size.second)
        params.x = position.first
        params.y = position.second
        return params
    }

    private fun computeBox(region: String): Pair<Int, Int> {
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        return when (region.lowercase()) {
            "left", "right" -> Pair((width * 0.5f).toInt(), (height * 0.6f).toInt())
            "top", "bottom" -> Pair((width * 0.6f).toInt(), (height * 0.5f).toInt())
            else -> Pair((width * 0.6f).toInt(), (height * 0.6f).toInt())
        }
    }

    private fun computePosition(region: String, boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val margin = dpToPx(16)
        return when (region.lowercase()) {
            "left" -> Pair(margin, (height - boxHeight) / 2)
            "right" -> Pair(width - boxWidth - margin, (height - boxHeight) / 2)
            "top" -> Pair((width - boxWidth) / 2, margin)
            "bottom" -> Pair((width - boxWidth) / 2, height - boxHeight - margin)
            else -> Pair((width - boxWidth) / 2, (height - boxHeight) / 2)
        }
    }

    private fun buildBorderDrawable(): GradientDrawable {
        val strokeWidth = dpToPx(3)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#22000000"))
            setStroke(strokeWidth, Color.parseColor("#FFCC00"))
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
