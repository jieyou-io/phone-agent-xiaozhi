package com.xiaozhi.phoneagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.effects.FlashEffect
import com.xiaozhi.phoneagent.effects.ShakeEffect
import com.xiaozhi.phoneagent.model.Action
import com.xiaozhi.phoneagent.model.TaskMessage
import com.xiaozhi.phoneagent.model.TranslationRegion
import com.xiaozhi.phoneagent.shell.ShizukuShell
import com.xiaozhi.phoneagent.ui.MainActivity
import com.xiaozhi.phoneagent.ui.overlay.CompositionOverlay
import com.xiaozhi.phoneagent.ui.overlay.DoudizhuOverlay
import com.xiaozhi.phoneagent.ui.overlay.TranslationOverlay
import com.xiaozhi.phoneagent.ui.overlay.TranslationRegionOverlay
import com.xiaozhi.phoneagent.utils.AppLauncher
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaozhi.phoneagent.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class AutomationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var automationJob: Job? = null

    private var currentTask: String = ""
    private var sessionId: String = ""
    private var stepCount = 0
    private val maxSteps = 100
    private var isStopping = false

    private var webSocket: WebSocketService? = null
    private val gson = Gson()
    private var lastScreenWidth = 1080
    private var lastScreenHeight = 1920
    private var lastScreenshotBase64: String? = null
    private var waitingTranslationRegion = false
    private var isDeviceBound = false

    private lateinit var translationOverlay: TranslationOverlay
    private lateinit var translationRegionOverlay: TranslationRegionOverlay
    private lateinit var compositionOverlay: CompositionOverlay
    private lateinit var doudizhuOverlay: DoudizhuOverlay
    private lateinit var shakeEffect: ShakeEffect
    private lateinit var flashEffect: FlashEffect

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = PrefsManager(this)
        if (prefs.showOverlay) {
            OverlayService.show(this)
        }
        translationOverlay = TranslationOverlay(this)
        translationRegionOverlay = TranslationRegionOverlay(
            this,
            onConfirm = { region -> handleTranslationRegion(region) },
            onClose = {
                waitingTranslationRegion = false
                finishAutomation()
            },
        )
        compositionOverlay = CompositionOverlay(this)
        doudizhuOverlay = DoudizhuOverlay(this)
        shakeEffect = ShakeEffect(this)
        flashEffect = FlashEffect(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: return START_NOT_STICKY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                initializeAndStart(task)
            }
            ACTION_STOP -> stopAutomation()
        }
        return START_NOT_STICKY
    }

    private fun initializeAndStart(task: String) {
        currentTask = task
        sessionId = UUID.randomUUID().toString()
        stepCount = 0
        isStopping = false

        if (!ensureShizukuReady()) {
            log("❌ Shizuku 未就绪，自动化服务终止")
            finishAutomation()
            return
        }

        startAutomationLoop()
    }

    private fun startAutomationLoop() {
        automationJob?.cancel()
        automationJob = serviceScope.launch {
            val prefs = PrefsManager(this@AutomationService)
            val wsUrl = prefs.webSocketUrl
            updateStatus(AutomationStatus.EXECUTING)
            log("开始执行任务: $currentTask")
            log("准备连接后端: $wsUrl")

            webSocket = WebSocketService(
                onOpen = {
                    log("WebSocket connected")
                    val prefs = PrefsManager(this@AutomationService)
                    webSocket?.sendBind(
                        deviceId = prefs.deviceId,
                        sessionId = sessionId
                    )
                    log("已发送设备绑定: deviceId=${prefs.deviceId}, 等待确认...")
                },
                onMessage = { message -> log(message) },
                onEvent = { type, payload -> handleEvent(type, payload) },
                onError = { error ->
                    log("WebSocket error: $error")
                    finishAutomation()
                },
                onClosed = {
                    if (!isStopping) {
                        log("WebSocket closed")
                        finishAutomation()
                    }
                }
            ).also { it.connect(wsUrl) }
        }
    }

    private suspend fun sendStep() {
        log("sendStep called; jobActive=${automationJob?.isActive} stopping=$isStopping")
        if (isStopping) return
        if (!ensureShizukuReady()) {
            log("❌ Shizuku 未就绪，无法截图")
            finishAutomation()
            return
        }
        if (stepCount >= maxSteps) {
            log("已达到最大步数限制")
            finishAutomation()
            return
        }

        stepCount += 1
        updateStatus(AutomationStatus.THINKING)
        log("准备发送任务请求...")

        OverlayService.temporaryHide(this)
        delay(500)

        val screenshot = try {
            ShizukuShell.captureScreen()
        } finally {
            OverlayService.temporaryShow(this)
        }

        if (screenshot == null) {
            log("❌ 截图失败")
            finishAutomation()
            return
        }
        lastScreenWidth = screenshot.width
        lastScreenHeight = screenshot.height

        val payload = buildTaskPayload(screenshot)
        log("已构造请求，发送到后端")
        webSocket?.sendTask(payload)
    }

    private fun buildTaskPayload(bitmap: Bitmap): TaskMessage {
        val base64 = bitmapToBase64(bitmap)
        lastScreenshotBase64 = base64
        return buildTaskPayload(base64, null)
    }

    private fun buildTaskPayload(base64: String, translationRegion: TranslationRegion?): TaskMessage {
        return TaskMessage(
            session_id = sessionId,
            task = currentTask,
            screenshot = base64,
            translation_region = translationRegion,
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = scaleBitmap(bitmap, MAX_IMAGE_EDGE)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxEdge && height <= maxEdge) {
            return bitmap
        }
        val scale = if (width >= height) {
            maxEdge.toFloat() / width.toFloat()
        } else {
            maxEdge.toFloat() / height.toFloat()
        }
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun handleEvent(type: String, payload: JsonObject) {
        when (type) {
            "bind_ack" -> {
                if (isDeviceBound) {
                    log("⚠ 收到重复的 bind_ack，忽略")
                    return
                }
                isDeviceBound = true
                log("✓ 设备绑定成功，开始执行任务")
                serviceScope.launch { sendStep() }
            }
            "action" -> handleAction(payload)
            "effect" -> handleEffects(payload)
            "error" -> {
                val message = payload.get("message")?.asString ?: "Unknown error"
                log("❌ Backend error: $message")
                finishAutomation()
            }
        }
    }

    private fun handleAction(payload: JsonObject) {
        val actionObj = payload.get("action")?.asJsonObject ?: return
        val action = parseAction(actionObj) ?: return
        serviceScope.launch {
            updateStatus(AutomationStatus.EXECUTING)
            val prefs = PrefsManager(this@AutomationService)
            val result = executeAction(action)
            if (result == StepResult.FINISHED) {
                finishAutomation()
                return@launch
            }
            if (result == StepResult.ERROR) {
                finishAutomation()
                return@launch
            }
            delay(prefs.actionDelay.toLong())
            sendStep()
        }
    }

    private fun handleEffects(payload: JsonObject) {
        val effects = payload.get("effects")?.asJsonArray ?: return
        for (effect in effects) {
            val obj = effect.asJsonObject
            val effectType = obj.get("type")?.asString ?: continue
            val data = obj.getAsJsonObject("payload")
            when (effectType) {
                "alert" -> {
                    val intensity = data?.get("intensity")?.asString ?: "medium"
                    val colorStr = data?.get("color")?.asString ?: "#FF3B30"
                    val duration = data?.get("duration_ms")?.asLong ?: 1200L
                    shakeEffect.trigger(intensity)
                    val color = try {
                        android.graphics.Color.parseColor(colorStr)
                    } catch (e: IllegalArgumentException) {
                        android.graphics.Color.parseColor("#FF3B30")
                    }
                    flashEffect.trigger(color, duration)
                }
                "shake" -> {
                    val intensity = data?.get("intensity")?.asString ?: "low"
                    shakeEffect.trigger(intensity)
                }
                "flash" -> {
                    val colorStr = data?.get("color")?.asString ?: "#FF0000"
                    val duration = data?.get("duration")?.asLong ?: 1500L
                    val color = try {
                        android.graphics.Color.parseColor(colorStr)
                    } catch (e: IllegalArgumentException) {
                        android.graphics.Color.parseColor("#FF0000")
                    }
                    flashEffect.trigger(color, duration)
                }
                "translation_request" -> {
                    waitingTranslationRegion = true
                    if (!translationRegionOverlay.show()) {
                        waitingTranslationRegion = false
                        finishAutomation()
                    }
                }
                "translation" -> {
                    val text = data?.get("text")?.asString ?: ""
                    translationOverlay.show(text)
                    if (waitingTranslationRegion) {
                        waitingTranslationRegion = false
                        finishAutomation()
                    }
                }
                "composition_hint" -> {
                    val region = data?.get("region")?.asString ?: "center"
                    val direction = data?.get("direction")?.asString ?: "none"
                    compositionOverlay.show(region, direction)
                }
                "composition_tap" -> {
                    if (data == null) {
                        log("⚠️ composition_tap 缺少 payload")
                        continue
                    }

                    val xNormElem = data.get("x_norm")
                    val yNormElem = data.get("y_norm")
                    val confidenceElem = data.get("confidence")

                    val xNorm = if (xNormElem?.isJsonPrimitive == true && xNormElem.asJsonPrimitive.isNumber) {
                        xNormElem.asDouble
                    } else null

                    val yNorm = if (yNormElem?.isJsonPrimitive == true && yNormElem.asJsonPrimitive.isNumber) {
                        yNormElem.asDouble
                    } else null

                    val confidence = if (confidenceElem?.isJsonPrimitive == true && confidenceElem.asJsonPrimitive.isNumber) {
                        confidenceElem.asDouble
                    } else null

                    if (xNorm == null || yNorm == null || confidence == null) {
                        log("⚠️ composition_tap 必需字段缺失或格式错误")
                        continue
                    }

                    if (confidence < 0.7) {
                        log("⚠️ composition_tap 置信度过低 ($confidence < 0.7), 忽略自动点击")
                        continue
                    }

                    if (xNorm !in 0.0..1.0 || yNorm !in 0.0..1.0) {
                        log("⚠️ composition_tap 坐标超出范围: x=$xNorm, y=$yNorm")
                        continue
                    }

                    val xPx = (xNorm * lastScreenWidth).roundToInt().coerceIn(0, lastScreenWidth - 1)
                    val yPx = (yNorm * lastScreenHeight).roundToInt().coerceIn(0, lastScreenHeight - 1)
                    val rule = data.get("rule")?.asString ?: "unknown"
                    val note = data.get("note")?.asString ?: ""

                    log("🎯 自动构图点击: 规则=$rule, 归一化坐标=($xNorm, $yNorm), 像素坐标=($xPx, $yPx)")
                    if (note.isNotEmpty()) {
                        log("   提示: $note")
                    }

                    serviceScope.launch {
                        if (!ensureShizukuReady()) {
                            log("❌ Shizuku 未就绪，无法执行构图点击")
                            return@launch
                        }
                        ShizukuShell.tap(xPx, yPx)
                    }
                }
                "doudizhu_suggestion" -> {
                    val text = data?.get("text")?.asString ?: ""
                    doudizhuOverlay.showSuggestion(text)
                }
            }
        }
    }

    private fun handleTranslationRegion(region: TranslationRegion) {
        if (isStopping) return
        val screenshot = lastScreenshotBase64
        if (screenshot.isNullOrEmpty()) {
            log("❌ 翻译区域请求缺少截图")
            return
        }
        if (stepCount >= maxSteps) {
            log("已达到最大步数限制")
            finishAutomation()
            return
        }
        stepCount += 1
        updateStatus(AutomationStatus.THINKING)
        log("准备发送翻译区域请求...")
        val payload = buildTaskPayload(screenshot, region)
        webSocket?.sendTask(payload)
    }

    private fun parseAction(actionObj: JsonObject): Action? {
        val metadata = actionObj.get("_metadata")?.asString
        if (metadata == "finish") {
            val message = actionObj.get("message")?.asString ?: ""
            return Action.Finish(message)
        }
        if (metadata == "do") {
            return parseDoAction(actionObj)
        }

        val rawType = actionObj.get("type")?.asString ?: return null
        val type = rawType.lowercase()
        return when (type) {
            "click", "tap" -> {
                val (x, y) = extractPoint(actionObj)
                Action.Do.Tap(x, y)
            }
            "swipe" -> {
                val points = extractSwipe(actionObj) ?: return null
                Action.Do.Swipe(points[0], points[1], points[2], points[3])
            }
            "type" -> {
                val text = actionObj.get("text")?.asString ?: ""
                Action.Do.Type(text)
            }
            "launch" -> {
                val app = actionObj.get("app")?.asString ?: ""
                Action.Do.Launch(app)
            }
            "back" -> Action.Do.Back
            "home" -> Action.Do.Home
            "wait" -> {
                val seconds = extractSeconds(actionObj)
                Action.Do.Wait(seconds)
            }
            "long_press" -> {
                val (x, y) = extractPoint(actionObj)
                Action.Do.LongPress(x, y)
            }
            "double_tap" -> {
                val (x, y) = extractPoint(actionObj)
                Action.Do.DoubleTap(x, y)
            }
            "finish" -> {
                val message = actionObj.get("message")?.asString ?: ""
                Action.Finish(message)
            }
            else -> null
        }
    }

    private fun parseDoAction(actionObj: JsonObject): Action? {
        val actionName = actionObj.get("action")?.asString ?: return null
        return when (actionName.lowercase()) {
            "tap" -> {
                val (x, y) = extractElementPoint(actionObj)
                Action.Do.Tap(x, y)
            }
            "swipe" -> {
                val points = extractSwipe(actionObj) ?: return null
                Action.Do.Swipe(points[0], points[1], points[2], points[3])
            }
            "type", "type_name" -> {
                val text = actionObj.get("text")?.asString ?: ""
                Action.Do.Type(text)
            }
            "launch" -> {
                val app = actionObj.get("app")?.asString ?: ""
                Action.Do.Launch(app)
            }
            "back" -> Action.Do.Back
            "home" -> Action.Do.Home
            "wait" -> {
                val seconds = extractSeconds(actionObj)
                Action.Do.Wait(seconds)
            }
            "long press" -> {
                val (x, y) = extractElementPoint(actionObj)
                Action.Do.LongPress(x, y)
            }
            "double tap" -> {
                val (x, y) = extractElementPoint(actionObj)
                Action.Do.DoubleTap(x, y)
            }
            "interact", "take_over", "note", "call_api" -> {
                val message = actionObj.get("message")?.asString ?: "需要用户处理的操作"
                Action.Finish(message)
            }
            else -> null
        }
    }

    private fun extractElementPoint(actionObj: JsonObject): Pair<Int, Int> {
        val elementArray = actionObj.getAsJsonArray("element")
        val x = elementArray?.getOrNullSafe(0)?.asInt ?: 0
        val y = elementArray?.getOrNullSafe(1)?.asInt ?: 0
        return Pair(x, y)
    }

    private fun extractPoint(actionObj: JsonObject): Pair<Int, Int> {
        val elementArray = actionObj.getAsJsonArray("element")
        val x = actionObj.get("x")?.asInt
            ?: elementArray?.getOrNullSafe(0)?.asInt
            ?: 0
        val y = actionObj.get("y")?.asInt
            ?: elementArray?.getOrNullSafe(1)?.asInt
            ?: 0
        return Pair(x, y)
    }

    private fun extractSwipe(actionObj: JsonObject): IntArray? {
        val startArray = actionObj.getAsJsonArray("start")
        val endArray = actionObj.getAsJsonArray("end")
        if (startArray != null && endArray != null) {
            return intArrayOf(
                startArray.getOrNullSafe(0)?.asInt ?: 0,
                startArray.getOrNullSafe(1)?.asInt ?: 0,
                endArray.getOrNullSafe(0)?.asInt ?: 0,
                endArray.getOrNullSafe(1)?.asInt ?: 0,
            )
        }
        val x1 = actionObj.get("x1")?.asInt
        val y1 = actionObj.get("y1")?.asInt
        val x2 = actionObj.get("x2")?.asInt
        val y2 = actionObj.get("y2")?.asInt
        return if (x1 != null && y1 != null && x2 != null && y2 != null) {
            intArrayOf(x1, y1, x2, y2)
        } else {
            null
        }
    }

    private fun extractSeconds(actionObj: JsonObject): Float {
        if (actionObj.has("seconds")) return actionObj.get("seconds").asFloat
        if (actionObj.has("duration")) {
            val raw = actionObj.get("duration")
            return if (raw.isJsonPrimitive && raw.asJsonPrimitive.isString) {
                raw.asString.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 1f
            } else {
                raw.asFloat
            }
        }
        if (actionObj.has("duration_ms")) return actionObj.get("duration_ms").asFloat / 1000f
        return 1f
    }

    private suspend fun executeAction(action: Action): StepResult {
        return when (action) {
            is Action.Finish -> {
                log("✅ 任务完成: ${action.message}")
                StepResult.FINISHED
            }
            is Action.Do -> {
                if (!ensureShizukuReady()) {
                    log("❌ Shizuku 未就绪，无法执行设备操作")
                    return StepResult.ERROR
                }
                val success = executeDoAction(action)
                if (success) {
                    log("✓ 动作执行成功")
                    StepResult.CONTINUE
                } else {
                    log("✗ 动作执行失败")
                    StepResult.ERROR
                }
            }
        }
    }

    private suspend fun executeDoAction(action: Action.Do): Boolean {
        val screenWidth = lastScreenWidth
        val screenHeight = lastScreenHeight
        return when (action) {
            is Action.Do.Tap -> {
                val x = scaleCoord(action.x, screenWidth)
                val y = scaleCoord(action.y, screenHeight)
                log("🎯 点击: ($x, $y)")
                ShizukuShell.tap(x, y)
            }
            is Action.Do.Swipe -> {
                val x1 = scaleCoord(action.x1, screenWidth)
                val y1 = scaleCoord(action.y1, screenHeight)
                val x2 = scaleCoord(action.x2, screenWidth)
                val y2 = scaleCoord(action.y2, screenHeight)
                log("👆 滑动: ($x1, $y1) -> ($x2, $y2)")
                ShizukuShell.swipe(x1, y1, x2, y2)
            }
            is Action.Do.Type -> {
                log("⌨️ 输入: ${action.text}")
                ShizukuShell.inputText(action.text)
            }
            is Action.Do.Launch -> {
                val packageName = AppLauncher.getPackageName(this, action.app)
                if (packageName != null) {
                    log("🚀 启动应用: ${action.app}")
                    ShizukuShell.launchApp(packageName)
                } else {
                    log("❌ 未找到应用: ${action.app}")
                    false
                }
            }
            is Action.Do.Back -> {
                log("⬅️ 返回")
                ShizukuShell.back()
            }
            is Action.Do.Home -> {
                log("🏠 回到桌面")
                ShizukuShell.home()
            }
            is Action.Do.Wait -> {
                log("⏳ 等待 ${action.seconds} 秒")
                delay((action.seconds * 1000).toLong())
                true
            }
            is Action.Do.LongPress -> {
                val x = scaleCoord(action.x, screenWidth)
                val y = scaleCoord(action.y, screenHeight)
                log("👆 长按: ($x, $y)")
                val result = ShizukuShell.execute("input swipe $x $y $x $y 1000").success
                delay(1000)
                result
            }
            is Action.Do.DoubleTap -> {
                val x = scaleCoord(action.x, screenWidth)
                val y = scaleCoord(action.y, screenHeight)
                log("👆👆 双击: ($x, $y)")
                ShizukuShell.tap(x, y)
                delay(100)
                ShizukuShell.tap(x, y)
            }
        }
    }

    private fun scaleCoord(value: Int, max: Int): Int {
        return if (value in 0..1000 && max > 1000) {
            value * max / 1000
        } else {
            value
        }
    }

    private fun JsonArray.getOrNullSafe(index: Int) = if (index in 0 until size()) get(index) else null

    private fun ensureShizukuReady(): Boolean {
        if (!ShizukuShell.isAvailable) {
            log("❌ Shizuku 服务未运行或不可用")
            log("💡 请确保已启动 Shizuku 应用")
            return false
        }
        if (!ShizukuShell.hasPermission) {
            log("❌ Shizuku 权限未授予")
            log("💡 请在 Shizuku 应用中授予本应用权限")
            return false
        }
        return true
    }

    private fun stopAutomation() {
        automationJob?.cancel()
        finishAutomation()
    }

    private fun finishAutomation() {
        isStopping = true
        updateStatus(AutomationStatus.IDLE)
        val prefs = PrefsManager(this)
        if (prefs.showOverlay) {
            OverlayService.show(this)
        }
        webSocket?.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        sendBroadcast(Intent(ACTION_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MESSAGE, message)
        })
    }

    private fun updateStatus(status: AutomationStatus) {
        _statusFlow.value = status
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status.name)
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "手机自动化服务运行中"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能助手")
            .setContentText("自动化任务运行中...")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        webSocket?.disconnect()
        super.onDestroy()
    }

    enum class StepResult {
        CONTINUE, FINISHED, ERROR
    }

    companion object {
        private const val TAG = "AutomationService"
        private const val CHANNEL_ID = "automation_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.xiaozhi.phoneagent.START"
        const val ACTION_STOP = "com.xiaozhi.phoneagent.STOP"
        const val ACTION_LOG = "com.xiaozhi.phoneagent.LOG"
        const val ACTION_STATUS = "com.xiaozhi.phoneagent.STATUS"

        const val EXTRA_TASK = "task"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_STATUS = "status"
        private const val MAX_IMAGE_EDGE = 1280
        private const val JPEG_QUALITY = 70

        private val _statusFlow = MutableStateFlow(AutomationStatus.IDLE)
        val statusFlow = _statusFlow.asStateFlow()

        fun start(context: Context, task: String) {
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK, task)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutomationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

enum class AutomationStatus {
    IDLE, LISTENING, THINKING, EXECUTING
}
