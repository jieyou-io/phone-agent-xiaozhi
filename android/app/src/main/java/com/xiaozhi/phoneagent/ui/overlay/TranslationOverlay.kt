package com.xiaozhi.phoneagent.ui.overlay

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.TextView

class TranslationOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#CC000000"))
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(24, 16, 24, 16)
    }
    private var attached = false
    private var scaleFactor = 1f
    private var lastX = 0
    private var lastY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var layoutParams: WindowManager.LayoutParams? = null
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.6f, 2.5f)
            view.scaleX = scaleFactor
            view.scaleY = scaleFactor
            return true
        }
    })

    fun show(text: String) {
        if (!canDraw()) return
        view.text = text
        if (!attached) {
            layoutParams = createLayoutParams()
            attachDragListener()
            windowManager.addView(view, layoutParams)
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
    }

    private fun attachDragListener() {
        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    lastX = layoutParams?.x ?: 0
                    lastY = layoutParams?.y ?: 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()
                        layoutParams?.let { params ->
                            params.x = lastX + dx
                            params.y = lastY + dy
                            windowManager.updateViewLayout(view, params)
                        }
                    }
                }
            }
            true
        }
    }
}
