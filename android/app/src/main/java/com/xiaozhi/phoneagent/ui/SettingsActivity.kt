package com.xiaozhi.phoneagent.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.service.OverlayService
import com.xiaozhi.phoneagent.utils.PrefsManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PrefsManager

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var modelNameInput: TextInputEditText

    // Baidu Speech UI
    private lateinit var baiduEnableSwitch: SwitchMaterial
    private lateinit var baiduAppIdLayout: TextInputLayout
    private lateinit var baiduAppIdInput: TextInputEditText
    private lateinit var baiduApiKeyLayout: TextInputLayout
    private lateinit var baiduApiKeyInput: TextInputEditText
    private lateinit var baiduSecretKeyLayout: TextInputLayout

    private lateinit var baiduSecretKeyInput: TextInputEditText
    private lateinit var voiceAutoSendSwitch: SwitchMaterial
    private lateinit var overlayEnableSwitch: SwitchMaterial
    
    private lateinit var actionDelaySlider: com.google.android.material.slider.Slider
    private lateinit var actionDelayValue: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PrefsManager(this)

        // Model API inputs
        apiKeyInput = findViewById(R.id.apiKeyInput)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        modelNameInput = findViewById(R.id.modelNameInput)

        // Baidu Speech inputs
        baiduEnableSwitch = findViewById(R.id.baiduEnableSwitch)
        baiduAppIdLayout = findViewById(R.id.baiduAppIdLayout)
        baiduAppIdInput = findViewById(R.id.baiduAppIdInput)
        baiduApiKeyLayout = findViewById(R.id.baiduApiKeyLayout)
        baiduApiKeyInput = findViewById(R.id.baiduApiKeyInput)
        baiduSecretKeyLayout = findViewById(R.id.baiduSecretKeyLayout)

        baiduSecretKeyInput = findViewById(R.id.baiduSecretKeyInput)
        voiceAutoSendSwitch = findViewById(R.id.voiceAutoSendSwitch)
        overlayEnableSwitch = findViewById(R.id.overlayEnableSwitch)

        actionDelaySlider = findViewById(R.id.actionDelaySlider)
        actionDelayValue = findViewById(R.id.actionDelayValue)

        loadSettings()
        setupListeners()
    }

    private fun setupListeners() {
        baiduEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateBaiduFieldsVisibility(isChecked)
        }

        actionDelaySlider.addOnChangeListener { _, value, _ ->
            actionDelayValue.text = "${value.toInt()} ms"
        }

        findViewById<android.widget.Button>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAppManage).setOnClickListener {
            startActivity(android.content.Intent(this, AppManageActivity::class.java))
        }

        findViewById<android.widget.Button>(R.id.cancelButton).setOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        apiKeyInput.setText(prefs.apiKey ?: "")
        baseUrlInput.setText(prefs.baseUrl ?: PrefsManager.DEFAULT_BASE_URL)
        modelNameInput.setText(prefs.modelName ?: PrefsManager.DEFAULT_MODEL_NAME)

        // Baidu Speech settings
        baiduEnableSwitch.isChecked = prefs.useBaiduSpeech
        baiduAppIdInput.setText(prefs.baiduAppId ?: "")
        baiduApiKeyInput.setText(prefs.baiduApiKey ?: "")
        baiduSecretKeyInput.setText(prefs.baiduSecretKey ?: "")

        updateBaiduFieldsVisibility(prefs.useBaiduSpeech)
        voiceAutoSendSwitch.isChecked = prefs.voiceAutoSend
        overlayEnableSwitch.isChecked = prefs.showOverlay

        actionDelaySlider.value = prefs.actionDelay.toFloat()
        actionDelayValue.text = "${prefs.actionDelay} ms"
    }

    private fun updateBaiduFieldsVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        baiduAppIdLayout.visibility = visibility
        baiduApiKeyLayout.visibility = visibility
        baiduSecretKeyLayout.visibility = visibility
    }

    private fun saveSettings() {
        val apiKey = apiKeyInput.text?.toString()?.trim()
        val baseUrl = baseUrlInput.text?.toString()?.trim()
        val modelName = modelNameInput.text?.toString()?.trim()

        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_api_key_required, Toast.LENGTH_SHORT).show()
            return
        }

        // Save Model API settings
        prefs.apiKey = apiKey
        prefs.baseUrl = if (baseUrl.isNullOrEmpty()) PrefsManager.DEFAULT_BASE_URL else baseUrl
        prefs.modelName = if (modelName.isNullOrEmpty()) PrefsManager.DEFAULT_MODEL_NAME else modelName

        // Save Baidu Speech settings
        val baiduEnabled = baiduEnableSwitch.isChecked
        prefs.useBaiduSpeech = baiduEnabled

        if (baiduEnabled) {
            val baiduAppId = baiduAppIdInput.text?.toString()?.trim()
            val baiduApiKey = baiduApiKeyInput.text?.toString()?.trim()
            val baiduSecretKey = baiduSecretKeyInput.text?.toString()?.trim()

            if (baiduAppId.isNullOrEmpty() || baiduApiKey.isNullOrEmpty() || baiduSecretKey.isNullOrEmpty()) {
                Toast.makeText(this, R.string.error_baidu_config_required, Toast.LENGTH_SHORT).show()
                return
            }

            prefs.baiduAppId = baiduAppId
            prefs.baiduApiKey = baiduApiKey
            prefs.baiduSecretKey = baiduSecretKey

        }
        
        // Save Voice Auto Send setting
        prefs.voiceAutoSend = voiceAutoSendSwitch.isChecked
        
        // Save Overlay setting
        val showOverlay = overlayEnableSwitch.isChecked
        prefs.showOverlay = showOverlay
        
        // 实时应用悬浮窗设置
        if (showOverlay) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                OverlayService.show(this)
            }
        } else {
            OverlayService.hide(this)
        }

        // Save Action Delay setting
        prefs.actionDelay = actionDelaySlider.value.toInt()

        Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
        finish()
    }
}
