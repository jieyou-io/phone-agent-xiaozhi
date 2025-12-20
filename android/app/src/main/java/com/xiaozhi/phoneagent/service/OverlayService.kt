package com.xiaozhi.phoneagent.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.databinding.OverlayBubbleBinding
import com.xiaozhi.phoneagent.databinding.OverlayPanelBinding
import com.xiaozhi.phoneagent.ui.MainActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.properties.Delegates

class OverlayService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager by Delegates.notNull()
    private var bubbleBinding: OverlayBubbleBinding? = null
    private var bubbleView: View? = null
    private var panelBinding: OverlayPanelBinding? = null
    private var panelView: View? = null

    private var currentStatus = AutomationStatus.IDLE
    private var isTemporarilyHidden = false
    private var isExpanded = false

    private var pulseAnimator: ObjectAnimator? = null
    private var edgeAnimator: ValueAnimator? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var isEdgeHidden = false

    // Touch and interaction state
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastClickTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retractRunnable = Runnable {
        if (!isExpanded && !isTemporarilyHidden && !isDragging) {
            hideBubbleToEdge()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // AutomationService.ACTION_STATUS -> { // This is now handled by statusFlow
                //     val statusName = intent.getStringExtra(AutomationService.EXTRA_STATUS)
                //     val status = AutomationStatus.valueOf(statusName ?: "IDLE")
                //     updateStatus(status)
                // }
                AutomationService.ACTION_LOG -> {
                    val message = intent.getStringExtra(AutomationService.EXTRA_LOG_MESSAGE)
                    updateActionText(message ?: "")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: initializing OverlayService")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        registerReceiver()
        observeStatusFlow()
        Log.d(TAG, "onCreate: service initialized, canDrawOverlays=${Settings.canDrawOverlays(this)}")
    }

    private fun observeStatusFlow() {
        serviceScope.launch {
            AutomationService.statusFlow.collect { status ->
                Log.d(TAG, "StatusFlow update: $status")
                updateStatus(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            ACTION_TEMP_HIDE -> temporaryHideBubble()
            ACTION_TEMP_SHOW -> temporaryShowBubble()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubbleView != null) {
            Log.d(TAG, "Bubble already exists")
            return
        }

        if (!com.xiaozhi.phoneagent.utils.PrefsManager(this).showOverlay) {
            Log.d(TAG, "Overlay disabled in settings, skipping showBubble")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted")
            return
        }

        Log.d(TAG, "Creating bubble view")
        bubbleBinding = OverlayBubbleBinding.inflate(LayoutInflater.from(this))
        bubbleView = bubbleBinding?.root

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
        val params = bubbleParams!!

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = isDragging
                    isDragging = false // 必须立即重置，否则 retractRunnable 里的检查会一直为 true
                    if (!wasDragging) {
                        // 如果悬浮窗被隐藏在边缘，先完全显示
                        if (isEdgeHidden) {
                            showBubbleFromEdge()
                            scheduleRetraction()
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime < 300) {
                                // Double tap - hide
                                hideBubble()
                            } else {
                                // Single tap - toggle panel
                                togglePanel()
                                if (!isExpanded) scheduleRetraction() else cancelRetraction()
                            }
                            lastClickTime = currentTime
                        }
                    } else {
                        // 拖动结束后，先贴边，1秒后回缩
                        snapToEdge()
                        scheduleRetraction()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubbleView, params)
            Log.d(TAG, "Bubble view added successfully")
            updateStatus(currentStatus)
            scheduleRetraction()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
            try { windowManager.removeView(bubbleView) } catch (_: Exception) { }
            bubbleView = null
            bubbleBinding = null
        }
    }

    private fun hideBubble() {
        hidePanel()
        pulseAnimator?.cancel()
        edgeAnimator?.cancel()
        bubbleView?.let {
            windowManager.removeView(it)
        }
        bubbleView = null
        bubbleBinding = null
        bubbleParams = null
        isEdgeHidden = false
    }

    private fun temporaryHideBubble() {
        if (isTemporarilyHidden) return
        
        Log.d(TAG, "Physically removing bubble and panel for screenshot")
        hideKeyboard()
        hidePanel()
        
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing bubble for temp hide", e)
            }
        }
        isTemporarilyHidden = true
    }

    private fun temporaryShowBubble() {
        if (bubbleView != null && isTemporarilyHidden) {
            Log.d(TAG, "Restoring bubble after screenshot")
            try {
                windowManager.addView(bubbleView, bubbleParams)
                updateStatus(currentStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring bubble: ${e.message}")
            }
            isTemporarilyHidden = false
        }
    }

    private fun togglePanel() {
        if (isExpanded) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelView != null) return

        panelBinding = OverlayPanelBinding.inflate(LayoutInflater.from(this))
        panelView = panelBinding?.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelBinding?.stopButton?.setOnClickListener {
            AutomationService.stop(this)
            hidePanel()
        }

        panelBinding?.quickSendButton?.setOnClickListener {
            sendQuickTask()
        }

        panelBinding?.quickTaskInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendQuickTask()
                true
            } else {
                false
            }
        }

        panelView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        windowManager.addView(panelView, params)
        isExpanded = true
    }

    private fun sendQuickTask() {
        val task = panelBinding?.quickTaskInput?.text?.toString()?.trim()
        if (task.isNullOrEmpty()) return

        panelBinding?.quickTaskInput?.setText("")
        hideKeyboard()
        hidePanel()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_QUICK_TASK
            putExtra(EXTRA_QUICK_TASK, task)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        panelBinding?.quickTaskInput?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun hidePanel() {
        hideKeyboard()
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Panel already removed or not attached")
            }
        }
        panelView = null
        panelBinding = null
        isExpanded = false
        scheduleRetraction()
    }

    private fun updateStatus(status: AutomationStatus) {
        currentStatus = status
        bubbleBinding?.apply {
            pulseAnimator?.cancel()

            idleView.visibility = View.GONE
            listeningView.visibility = View.GONE
            thinkingContainer.visibility = View.GONE
            executingContainer.visibility = View.GONE

            when (status) {
                AutomationStatus.IDLE -> {
                    idleView.visibility = View.VISIBLE
                    bubbleView?.alpha = 1.0f
                    bubbleView?.visibility = View.VISIBLE
                    // 任务结束，确保显示并延迟缩进
                    showBubbleFromEdge()
                    scheduleRetraction()
                }
                AutomationStatus.LISTENING -> {
                    listeningView.visibility = View.VISIBLE
                    startPulseAnimation(listeningView)
                    bubbleView?.alpha = 1.0f
                    bubbleView?.visibility = View.VISIBLE
                    showBubbleFromEdge()
                    cancelRetraction()
                }
                AutomationStatus.THINKING -> {
                    thinkingContainer.visibility = View.VISIBLE
                    bubbleView?.alpha = 1.0f 
                    bubbleView?.visibility = View.VISIBLE
                    cancelRetraction()
                }
                AutomationStatus.EXECUTING -> {
                    executingContainer.visibility = View.VISIBLE
                    bubbleView?.alpha = 1.0f
                    bubbleView?.visibility = View.VISIBLE
                    cancelRetraction()
                }
            }
        }
    }

    private fun scheduleRetraction() {
        mainHandler.removeCallbacks(retractRunnable)
        mainHandler.postDelayed(retractRunnable, 1000)
    }

    private fun cancelRetraction() {
        mainHandler.removeCallbacks(retractRunnable)
    }

    private fun snapToEdge() {
        bubbleParams?.let { params ->
            bubbleView?.let { view ->
                val isNearLeftEdge = params.x < screenWidth / 2
                val targetX = if (isNearLeftEdge) 20 else screenWidth - view.measuredWidth - 20
                
                edgeAnimator?.cancel()
                edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                    duration = 300
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        params.x = animator.animatedValue as Int
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    start()
                }
                isEdgeHidden = false
            }
        }
    }

    private fun startPulseAnimation(view: View) {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f, 1f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun updateActionText(text: String) {
        panelBinding?.currentActionText?.text = text
    }

    /**
     * 将悬浮窗隐藏到屏幕边缘
     */
    private fun hideBubbleToEdge() {
        bubbleParams?.let { params ->
            bubbleView?.let { view ->
                // 获取悬浮窗的宽度
                view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val bubbleWidth = view.measuredWidth
                val bubbleHeight = view.measuredHeight
                
                // 判断悬浮窗靠近哪一边
                val isNearLeftEdge = params.x < screenWidth / 2
                
                // 计算目标X坐标（只露出一小部分）
                val visiblePart = bubbleWidth / 4  // 露出1/4宽度
                val targetX = if (isNearLeftEdge) {
                    -bubbleWidth + visiblePart  // 靠左边隐藏
                } else {
                    screenWidth - visiblePart  // 靠右边隐藏
                }
                
                // 取消之前的动画
                edgeAnimator?.cancel()
                
                // 创建平滑的移动动画
                edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                    duration = 300
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        params.x = animator.animatedValue as Int
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout", e)
                            cancel()
                        }
                    }
                    start()
                }
                
                isEdgeHidden = true
                Log.d(TAG, "Hiding bubble to edge: targetX=$targetX, isLeft=$isNearLeftEdge")
            }
        }
    }

    /**
     * 从屏幕边缘完全显示悬浮窗
     */
    private fun showBubbleFromEdge() {
        if (!isEdgeHidden) return
        
        bubbleParams?.let { params ->
            bubbleView?.let { view ->
                // 判断在哪一边
                val isNearLeftEdge = params.x < 0
                
                // 计算目标X坐标（完全显示）
                val targetX = if (isNearLeftEdge) {
                    20  // 靠左边显示，留一点边距
                } else {
                    screenWidth - view.measuredWidth - 20  // 靠右边显示
                }
                
                // 取消之前的动画
                edgeAnimator?.cancel()
                
                // 创建平滑的移动动画
                edgeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                    duration = 200
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        params.x = animator.animatedValue as Int
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout", e)
                            cancel()
                        }
                    }
                    start()
                }
                
                isEdgeHidden = false
                Log.d(TAG, "Showing bubble from edge: targetX=$targetX")
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutomationService.ACTION_STATUS)
            addAction(AutomationService.ACTION_LOG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "悬浮窗服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能助手")
            .setContentText("悬浮窗已开启")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        hideBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 2

        const val ACTION_SHOW = "com.xiaozhi.phoneagent.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.xiaozhi.phoneagent.HIDE_OVERLAY"
        const val ACTION_TEMP_HIDE = "com.xiaozhi.phoneagent.TEMP_HIDE_OVERLAY"
        const val ACTION_TEMP_SHOW = "com.xiaozhi.phoneagent.TEMP_SHOW_OVERLAY"
        const val ACTION_QUICK_TASK = "com.xiaozhi.phoneagent.QUICK_TASK"
        const val EXTRA_QUICK_TASK = "quick_task"

        fun show(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.e(TAG, "Cannot show overlay: permission not granted")
                return
            }
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startForegroundService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun temporaryHide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TEMP_HIDE
            }
            context.startService(intent)
        }

        fun temporaryShow(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TEMP_SHOW
            }
            context.startService(intent)
        }
    }
}
