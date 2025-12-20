package com.xiaozhi.phoneagent.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.databinding.ActivityMainBinding
import com.xiaozhi.phoneagent.service.AutomationService
import com.xiaozhi.phoneagent.service.AutomationStatus
import com.xiaozhi.phoneagent.service.OverlayService
import com.xiaozhi.phoneagent.service.ScreenshotManager
import com.xiaozhi.phoneagent.shell.ShizukuShell
import com.xiaozhi.phoneagent.speech.BaiduSpeechManager
import com.xiaozhi.phoneagent.utils.PrefsManager
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    private var pendingTask: String? = null
    private var baiduSpeechManager: BaiduSpeechManager? = null

    private val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            super.onStop(owner)
            if (Settings.canDrawOverlays(this@MainActivity) && prefs.showOverlay) {
                OverlayService.show(this@MainActivity)
            }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatus()
            log("Shizuku 权限已授予")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutomationService.ACTION_STATUS -> {
                    val statusName = intent.getStringExtra(AutomationService.EXTRA_STATUS)
                    val status = AutomationStatus.valueOf(statusName ?: "IDLE")
                    updateStatus(status)
                }
                AutomationService.ACTION_LOG -> {
                    val message = intent.getStringExtra(AutomationService.EXTRA_LOG_MESSAGE)
                    log(message ?: "")
                }
            }
        }
    }



    private val speechRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                log("识别结果: $text")
                startAutomation(text)
            } else {
                log("未能识别语音")
                updateStatus(AutomationStatus.IDLE)
            }
        } else {
            log("语音识别已取消")
            updateStatus(AutomationStatus.IDLE)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, R.string.permission_audio_message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsManager(this)

        setupUI()
        checkPermissions()
        registerReceivers()
        initBaiduSpeech()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        lifecycle.addObserver(lifecycleObserver)

        handleQuickTaskIntent(intent)
    }

    private fun initBaiduSpeech() {
        if (prefs.isBaiduSpeechConfigured) {
            try {
                baiduSpeechManager = BaiduSpeechManager(this).apply {
                    if (initialize()) {
                        setListener(baiduSpeechListener)
                        log("百度语音识别已初始化")
                    } else {
                        log("百度语音识别初始化失败")
                    }
                }
            } catch (e: Exception) {
                log("百度语音SDK加载失败: ${e.message}")
                baiduSpeechManager = null
            }
        }
    }

    private val baiduSpeechListener = object : BaiduSpeechManager.SpeechListener {
        override fun onReady() {
            runOnUiThread {
                updateStatus(AutomationStatus.LISTENING)
                log("✅ 语音识别就绪，请说话...")
            }
        }

        override fun onResult(text: String) {
            runOnUiThread {
                if (text.isNotEmpty()) {
                    log("✅ 识别结果: $text")
                    if (prefs.voiceAutoSend) {
                        startAutomation(text)
                    } else {
                        binding.taskEditText.setText(text)
                        binding.taskEditText.setSelection(text.length)
                        updateStatus(AutomationStatus.IDLE)
                        Toast.makeText(this@MainActivity, "请确认并点击发送", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    log("⚠️ 未能识别到有效语音")
                    updateStatus(AutomationStatus.IDLE)
                }
            }
        }

        override fun onPartialResult(text: String) {
            runOnUiThread {
                if (text.isNotEmpty()) {
                    binding.statusTextView.text = "识别中: $text"
                }
            }
        }

        override fun onError(errorCode: Int, errorMessage: String) {
            runOnUiThread {
                log("❌ 语音识别错误[$errorCode]: $errorMessage")
                updateStatus(AutomationStatus.IDLE)
                Toast.makeText(this@MainActivity, "语音识别失败: $errorMessage", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onFinish() {
            runOnUiThread {
                updateStatus(AutomationStatus.IDLE)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleQuickTaskIntent(it) }
    }

    private fun handleQuickTaskIntent(intent: Intent) {
        if (intent.action == OverlayService.ACTION_QUICK_TASK) {
            val task = intent.getStringExtra(OverlayService.EXTRA_QUICK_TASK)
            if (!task.isNullOrEmpty()) {
                log("快捷指令: $task")
                if (!checkShizuku()) {
                    Toast.makeText(this, R.string.error_shizuku_required, Toast.LENGTH_SHORT).show()
                    return
                }
                if (!prefs.isConfigured) {
                    Toast.makeText(this, R.string.error_api_key_required, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return
                }
                if (pendingTask != null) {
                    Toast.makeText(this, "任务处理中，请稍候", Toast.LENGTH_SHORT).show()
                    return
                }
                startAutomation(task)
            }
        }
    }

    private fun setupUI() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.micButton.setOnClickListener {
            if (!checkShizuku()) return@setOnClickListener
            if (!prefs.isConfigured) {
                Toast.makeText(this, R.string.error_api_key_required, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            startListening()
        }

        binding.sendButton.setOnClickListener {
            handleTextInput()
        }

        binding.taskEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                handleTextInput()
                true
            } else {
                false
            }
        }

        updateShizukuStatus()
    }

    private fun checkPermissions() {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun checkShizuku(): Boolean {
        if (!ShizukuShell.isAvailable) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku")
                .setMessage(R.string.shizuku_install_hint)
                .setPositiveButton("下载 Shizuku") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                    startActivity(intent)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return false
        }

        if (!ShizukuShell.hasPermission) {
            ShizukuShell.requestPermission(SHIZUKU_PERMISSION_CODE)
            return false
        }

        return true
    }

    private fun updateShizukuStatus() {
        val isAvailable = ShizukuShell.isAvailable
        val hasPermission = isAvailable && ShizukuShell.hasPermission

        binding.shizukuIndicator.setBackgroundResource(
            if (hasPermission) R.drawable.bubble_listening else R.drawable.bubble_idle
        )
        binding.shizukuStatusText.text = when {
            !isAvailable -> getString(R.string.shizuku_not_running)
            !hasPermission -> getString(R.string.shizuku_permission_denied)
            else -> getString(R.string.shizuku_running)
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Haptic feedback
        vibrate()

        // Toggle logic
        if (binding.micButton.isSelected) {
            // Stopping
            log("🛑 正在停止录音...")
            baiduSpeechManager?.stopListening()
            return
        }

        // Starting
        // Visual feedback - update button and status immediately
        binding.micButton.isSelected = true
        binding.micButton.setImageResource(R.drawable.ic_stop) // Assuming you have a stop icon or toggle tint
        updateStatus(AutomationStatus.LISTENING)
        log("🎤 正在启动语音识别...")

        // Use Baidu Speech if configured, otherwise use system speech recognition
        if (prefs.isBaiduSpeechConfigured && baiduSpeechManager != null) {
            startBaiduListening()
        } else {
            startSystemListening()
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    private fun startBaiduListening() {
        try {
            log("🎤 百度语音识别已启动，请说话...")
            baiduSpeechManager?.startListening()
        } catch (e: Exception) {
            log("❌ 启动百度语音识别失败: ${e.message}")
            updateStatus(AutomationStatus.IDLE)
            // Fallback to system speech recognition
            startSystemListening()
        }
    }

    private fun startSystemListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您的指令...")
        }

        try {
            log("🎤 系统语音识别已启动，请说话...")
            speechRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            log("❌ 启动语音识别失败: ${e.message}")
            updateStatus(AutomationStatus.IDLE)
            Toast.makeText(this, "设备不支持语音识别", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTextInput() {
        val text = binding.taskEditText.text.toString().trim()
        if (text.isEmpty()) return

        if (!checkShizuku()) return
        if (!prefs.isConfigured) {
            Toast.makeText(this, R.string.error_api_key_required, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.taskEditText.windowToken, 0)

        binding.taskEditText.setText("")
        log("发送指令: $text")
        startAutomation(text)
    }

    private fun startAutomation(task: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_message, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Silent startup using Shizuku - no more screen capture permission prompts
        AutomationService.start(this, task)
        if (prefs.showOverlay) {
            OverlayService.show(this)
        }
        
        // 提示用户任务已启动
        log("任务已启动，正在后台运行...")
        Toast.makeText(this, "任务已启动，正在后台运行...", Toast.LENGTH_LONG).show()
        
        // 延迟最小化，让用户看到提示，体验更平滑
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            moveTaskToBack(true)
        }, 1500)
    }

    private fun updateStatus(status: AutomationStatus) {
        binding.statusTextView.text = when (status) {
            AutomationStatus.IDLE -> getString(R.string.status_idle)
            AutomationStatus.LISTENING -> getString(R.string.status_listening)
            AutomationStatus.THINKING -> getString(R.string.status_thinking)
            AutomationStatus.EXECUTING -> getString(R.string.status_executing)
        }

        // Update mic button state
        // ALWAYS enable button so user can stop recording manually
        binding.micButton.isEnabled = true 

        if (status == AutomationStatus.IDLE) {
            binding.micButton.isSelected = false
            binding.micButton.setImageResource(R.drawable.ic_mic) // Reset to mic icon
            binding.micButton.alpha = 1.0f
        } else if (status == AutomationStatus.LISTENING) {
            binding.micButton.isSelected = true
            binding.micButton.setImageResource(R.drawable.ic_stop) // Show stop icon
            binding.micButton.alpha = 1.0f
        } else {
            // THINKING or EXECUTING
            binding.micButton.isSelected = false
            binding.micButton.setImageResource(R.drawable.ic_mic)
            binding.micButton.alpha = 0.5f // Dim when busy
        }
    }

    private val logLines = mutableListOf<String>()
    private val maxLogLines = 100

    private fun log(message: String) {
        runOnUiThread {
            logLines.add(message)
            if (logLines.size > maxLogLines) {
                logLines.removeAt(0)
            }
            binding.logTextView.text = logLines.joinToString("\n")
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun registerReceivers() {
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

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        // Reinitialize Baidu Speech if settings changed
        if (prefs.isBaiduSpeechConfigured && baiduSpeechManager == null) {
            initBaiduSpeech()
        } else if (!prefs.isBaiduSpeechConfigured && baiduSpeechManager != null) {
            baiduSpeechManager?.release()
            baiduSpeechManager = null
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(statusReceiver)
        baiduSpeechManager?.release()
        baiduSpeechManager = null
        super.onDestroy()
    }

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 100
    }
}
