package com.xiaozhi.phoneagent.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.xiaozhi.phoneagent.model.TranslationRegion

class TranslationRegionOverlay(
    private val context: Context,
    private val onConfirm: (TranslationRegion) -> Unit,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rootView = FrameLayout(context)
    private val selectionView = FrameLayout(context)
    private val actionBar = LinearLayout(context)
    private val closeButton = Button(context)
    private val confirmButton = Button(context)
    private var attached = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scaleFactor = 1f
    private var lastX = 0
    private var lastY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.6f, 2.5f)
            selectionView.scaleX = scaleFactor
            selectionView.scaleY = scaleFactor
            return true
        }
    })

    init {
        rootView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val border = GradientDrawable().apply {
            setStroke(dp(2), Color.parseColor("#FFCC00"))
            setColor(Color.parseColor("#22000000"))
        }
        selectionView.background = border
        selectionView.layoutParams = FrameLayout.LayoutParams(dp(220), dp(160))

        actionBar.orientation = LinearLayout.HORIZONTAL
        actionBar.setPadding(dp(8), dp(8), dp(8), dp(8))
        actionBar.setBackgroundColor(Color.parseColor("#AA000000"))

        closeButton.text = "关闭"
        confirmButton.text = "确认"
        closeButton.setOnClickListener {
            hide()
            onClose()
        }
        confirmButton.setOnClickListener {
            val region = computeRegion()
            hide()
            onConfirm(region)
        }

        actionBar.addView(closeButton)
        actionBar.addView(confirmButton)
        selectionView.addView(actionBar)
        rootView.addView(selectionView)
        attachDragListener()
    }

    fun show(): Boolean {
        if (!canDraw()) return false
        if (!attached) {
            layoutParams = createLayoutParams()
            windowManager.addView(rootView, layoutParams)
            attached = true
        }
        return true
    }

    fun hide() {
        if (attached) {
            windowManager.removeView(rootView)
            attached = false
        }
    }

    private fun computeRegion(): TranslationRegion {
        val location = IntArray(2)
        selectionView.getLocationOnScreen(location)
        val width = (selectionView.width * selectionView.scaleX).toInt().coerceAtLeast(1)
        val height = (selectionView.height * selectionView.scaleY).toInt().coerceAtLeast(1)
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return TranslationRegion(
            x = location[0],
            y = location[1],
            width = width,
            height = height,
            screen_width = metrics.widthPixels,
            screen_height = metrics.heightPixels,
        )
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
            gravity = Gravity.TOP or Gravity.START
            x = dp(40)
            y = dp(140)
        }
    }

    private fun attachDragListener() {
        rootView.setOnTouchListener { _: View, event: MotionEvent ->
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
                            windowManager.updateViewLayout(rootView, params)
                        }
                    }
                }
            }
            true
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }
}
