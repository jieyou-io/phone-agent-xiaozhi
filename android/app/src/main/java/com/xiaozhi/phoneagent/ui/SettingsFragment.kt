package com.xiaozhi.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.button.MaterialButton
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.service.OverlayService
import com.xiaozhi.phoneagent.utils.PrefsManager
import com.xiaozhi.phoneagent.utils.HttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class SettingsFragment : Fragment() {
    private lateinit var prefs: PrefsManager

    private lateinit var modelBaseUrlLayout: TextInputLayout
    private lateinit var modelBaseUrlInput: TextInputEditText
    private lateinit var modelApiKeyLayout: TextInputLayout
    private lateinit var modelApiKeyInput: TextInputEditText
    private lateinit var modelNameLayout: TextInputLayout
    private lateinit var modelNameInput: TextInputEditText

    private lateinit var managerBaseUrlLayout: TextInputLayout
    private lateinit var managerBaseUrlInput: TextInputEditText
    private lateinit var managerApiKeyLayout: TextInputLayout
    private lateinit var managerApiKeyInput: TextInputEditText
    private lateinit var managerModelNameLayout: TextInputLayout
    private lateinit var managerModelNameInput: TextInputEditText

    private lateinit var baiduEnableSwitch: SwitchMaterial
    private lateinit var baiduConfigLayout: android.widget.LinearLayout
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())

        modelBaseUrlLayout = view.findViewById(R.id.layoutBaseUrl)
        modelBaseUrlInput = view.findViewById(R.id.inputBaseUrl)
        modelApiKeyLayout = view.findViewById(R.id.layoutApiKey)
        modelApiKeyInput = view.findViewById(R.id.inputApiKey)
        modelNameLayout = view.findViewById(R.id.layoutModel)
        modelNameInput = view.findViewById(R.id.inputModel)

        managerBaseUrlLayout = view.findViewById(R.id.managerBaseUrlLayout)
        managerBaseUrlInput = view.findViewById(R.id.managerBaseUrlInput)
        managerApiKeyLayout = view.findViewById(R.id.managerApiKeyLayout)
        managerApiKeyInput = view.findViewById(R.id.managerApiKeyInput)
        managerModelNameLayout = view.findViewById(R.id.managerModelNameLayout)
        managerModelNameInput = view.findViewById(R.id.managerModelNameInput)

        baiduEnableSwitch = view.findViewById(R.id.baiduEnableSwitch)
        baiduConfigLayout = view.findViewById(R.id.baiduConfigLayout)
        baiduAppIdLayout = view.findViewById(R.id.baiduAppIdLayout)
        baiduAppIdInput = view.findViewById(R.id.baiduAppIdInput)
        baiduApiKeyLayout = view.findViewById(R.id.baiduApiKeyLayout)
        baiduApiKeyInput = view.findViewById(R.id.baiduApiKeyInput)
        baiduSecretKeyLayout = view.findViewById(R.id.baiduSecretKeyLayout)
        baiduSecretKeyInput = view.findViewById(R.id.baiduSecretKeyInput)
        voiceAutoSendSwitch = view.findViewById(R.id.voiceAutoSendSwitch)
        overlayEnableSwitch = view.findViewById(R.id.overlayEnableSwitch)

        actionDelaySlider = view.findViewById(R.id.actionDelaySlider)
        actionDelayValue = view.findViewById(R.id.actionDelayValue)

        loadSettings()
        setupListeners()
    }

    private fun setupListeners() {
        // 模型配置保存按钮
        view?.findViewById<MaterialButton>(R.id.btnSaveModelConfig)?.setOnClickListener {
            saveModelConfig()
        }

        // 规划模型配置保存按钮
        view?.findViewById<MaterialButton>(R.id.btnSaveManagerModelConfig)?.setOnClickListener {
            saveManagerModelConfig()
        }

        // 百度语音识别开关 - 即时生效
        baiduEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.useBaiduSpeech = isChecked
            updateBaiduFieldsVisibility(isChecked)
        }

        // 语音自动发送开关 - 即时生效
        voiceAutoSendSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.voiceAutoSend = isChecked
        }

        // 悬浮球开关 - 即时生效
        overlayEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.showOverlay = isChecked
            if (isChecked) {
                if (android.provider.Settings.canDrawOverlays(requireContext())) {
                    OverlayService.show(requireContext())
                }
            } else {
                OverlayService.hide(requireContext())
            }
        }

        // 操作延迟滑块 - 即时生效
        actionDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val delay = value.toInt()
                actionDelayValue.text = "$delay ms"
                prefs.actionDelay = delay
            }
        }

        // 百度语音确定按钮 - 提交到后台
        view?.findViewById<MaterialButton>(R.id.btnBaiduConfirm)?.setOnClickListener {
            syncBaiduSettings()
        }

        // 应用管理按钮
        view?.findViewById<MaterialButton>(R.id.btnAppManage)?.setOnClickListener {
            startActivity(Intent(requireContext(), AppManageActivity::class.java))
        }
    }

    private fun loadSettings() {
        modelBaseUrlInput.setText(prefs.baseUrl ?: "")
        modelApiKeyInput.setText(prefs.apiKey ?: "")
        modelNameInput.setText(prefs.modelName ?: "")

        managerBaseUrlInput.setText(prefs.managerBaseUrl ?: "")
        managerApiKeyInput.setText(prefs.managerApiKey ?: "")
        managerModelNameInput.setText(prefs.managerModelName ?: "")

        baiduEnableSwitch.isChecked = prefs.useBaiduSpeech
        baiduAppIdInput.setText(prefs.baiduAppId ?: "")
        baiduApiKeyInput.setText(prefs.baiduApiKey ?: "")
        baiduSecretKeyInput.setText(prefs.baiduSecretKey ?: "")

        updateBaiduFieldsVisibility(prefs.useBaiduSpeech)
        voiceAutoSendSwitch.isChecked = prefs.voiceAutoSend
        overlayEnableSwitch.isChecked = prefs.showOverlay

        actionDelaySlider.value = prefs.actionDelay.toFloat()
        actionDelayValue.text = "${prefs.actionDelay} ms"

        fetchModelConfigFromBackend()
        fetchManagerModelFromBackend()
        fetchBaiduSpeechConfig()
    }

    private fun updateBaiduFieldsVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.GONE
        baiduConfigLayout.visibility = visibility
    }

    private fun syncBaiduSettings() {
        val baiduEnabled = baiduEnableSwitch.isChecked
        prefs.useBaiduSpeech = baiduEnabled

        if (!baiduEnabled) return

        val baiduAppId = baiduAppIdInput.text?.toString()?.trim()
        val baiduApiKey = baiduApiKeyInput.text?.toString()?.trim()
        val baiduSecretKey = baiduSecretKeyInput.text?.toString()?.trim()

        if (baiduAppId.isNullOrEmpty() || baiduApiKey.isNullOrEmpty() || baiduSecretKey.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.error_baidu_config_required, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.baiduAppId = baiduAppId
        prefs.baiduApiKey = baiduApiKey
        prefs.baiduSecretKey = baiduSecretKey

        syncBaiduSpeechConfig(prefs.deviceId, baiduAppId, baiduApiKey, baiduSecretKey)
    }

    private fun syncBaiduSpeechConfig(deviceId: String, appId: String, apiKey: String, secretKey: String) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip Baidu config sync")
            showToast("后端地址为空，无法同步百度配置")
            return
        }

        val payload = JSONObject().apply {
            put("app_id", appId)
            put("api_key", apiKey)
            put("secret_key", secretKey)
        }

        val updateUrl = "$backendUrl/api/baidu-speech-configs/$deviceId"
        val request = Request.Builder()
            .url(updateUrl)
            .put(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Baidu config update failed", e)
                val errorMsg = when {
                    e is java.net.SocketTimeoutException -> "连接超时，请检查后端服务是否启动"
                    e.message?.contains("Failed to connect") == true -> "无法连接后端，请检查服务器地址"
                    else -> "百度配置同步失败: ${e.message}"
                }
                showToast(errorMsg)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (response.code == 404) {
                        Log.d(TAG, "Baidu config not found, creating new")
                        createBaiduSpeechConfig(deviceId, payload)
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Baidu config update failed: ${response.code} ${response.message}")
                        showToast("百度配置同步失败")
                    } else {
                        Log.d(TAG, "Baidu config synced successfully")
                        showToast("百度配置已提交到后台")
                    }
                }
            }
        })
    }

    private fun createBaiduSpeechConfig(deviceId: String, payload: JSONObject) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) return

        val createPayload = JSONObject(payload.toString()).apply {
            put("owner_device_id", deviceId)
        }

        val request = Request.Builder()
            .url("$backendUrl/api/baidu-speech-configs")
            .post(createPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Baidu config create failed", e)
                val errorMsg = when {
                    e is java.net.SocketTimeoutException -> "连接超时，请检查后端服务是否启动"
                    e.message?.contains("Failed to connect") == true -> "无法连接后端，请检查服务器地址"
                    else -> "百度配置创建失败: ${e.message}"
                }
                showToast(errorMsg)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Baidu config create failed: ${response.code} ${response.message}")
                        showToast("百度配置创建失败")
                    } else {
                        Log.d(TAG, "Baidu config created successfully")
                        showToast("百度配置已提交到后台")
                    }
                }
            }
        })
    }

    private fun fetchModelConfigFromBackend() {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) return

        val deviceId = prefs.deviceId
        val request = Request.Builder()
            .url("$backendUrl/api/devices/$deviceId/default-model")
            .get()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Model config fetch failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (response.code == 404) {
                        Log.d(TAG, "No model config for device, using local prefs")
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Model config fetch failed: ${response.code} ${response.message}")
                        return
                    }

                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return

                    val baseUrl = json.optString("base_url").trim()
                    val apiKey = json.optString("api_key").trim()
                    val model = json.optString("model").trim()

                    if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                        Log.w(TAG, "Model config fetch returned incomplete payload")
                        return
                    }

                    prefs.baseUrl = baseUrl
                    prefs.apiKey = apiKey
                    prefs.modelName = model

                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (modelBaseUrlInput.text.isNullOrEmpty()) {
                            modelBaseUrlInput.setText(baseUrl)
                        }
                        if (modelApiKeyInput.text.isNullOrEmpty()) {
                            modelApiKeyInput.setText(apiKey)
                        }
                        if (modelNameInput.text.isNullOrEmpty()) {
                            modelNameInput.setText(model)
                        }
                    }
                }
            }
        })
    }

    private fun fetchBaiduSpeechConfig() {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) return

        val deviceId = prefs.deviceId
        val request = Request.Builder()
            .url("$backendUrl/api/baidu-speech-configs/$deviceId")
            .get()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Baidu config fetch failed (likely not configured)", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (response.code == 404) {
                        Log.d(TAG, "No Baidu config for device")
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Baidu config fetch failed: ${response.code} ${response.message}")
                        return
                    }

                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return

                    val appId = json.optString("app_id")
                    val apiKey = json.optString("api_key")
                    val secretKey = json.optString("secret_key")

                    activity?.runOnUiThread {
                        if (isAdded && baiduAppIdInput.text.isNullOrEmpty() &&
                            baiduApiKeyInput.text.isNullOrEmpty() &&
                            baiduSecretKeyInput.text.isNullOrEmpty()) {
                            baiduAppIdInput.setText(appId)
                            baiduApiKeyInput.setText(apiKey)
                            baiduSecretKeyInput.setText(secretKey)
                        }
                    }
                }
            }
        })
    }

    private fun handleUnauthorized() {
        Log.w(TAG, "Session expired, redirecting to login")
        HttpClient.clearCookies()
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            activity?.finish()
        }
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveModelConfig() {
        val baseUrl = modelBaseUrlInput.text?.toString()?.trim()
        val apiKey = modelApiKeyInput.text?.toString()?.trim()
        val model = modelNameInput.text?.toString()?.trim()

        modelBaseUrlLayout.error = null
        modelApiKeyLayout.error = null
        modelNameLayout.error = null

        if (baseUrl.isNullOrEmpty()) {
            modelBaseUrlLayout.error = "请输入 Base URL"
            return
        }
        if (apiKey.isNullOrEmpty()) {
            modelApiKeyLayout.error = "请输入 API Key"
            return
        }
        if (model.isNullOrEmpty()) {
            modelNameLayout.error = "请输入模型名称"
            return
        }

        prefs.baseUrl = baseUrl
        prefs.apiKey = apiKey
        prefs.modelName = model

        syncModelConfigToBackend(prefs.deviceId, baseUrl, apiKey, model)
    }

    private fun syncModelConfigToBackend(deviceId: String, baseUrl: String, apiKey: String, model: String) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip model config sync")
            showToast("后端地址为空，无法同步模型配置")
            return
        }

        val payload = JSONObject().apply {
            put("provider", "openai")
            put("base_url", baseUrl)
            put("api_key", apiKey)
            put("model", model)
        }

        val request = Request.Builder()
            .url("$backendUrl/api/devices/$deviceId/default-model")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Model config sync failed", e)
                val errorMsg = when {
                    e is java.net.SocketTimeoutException -> "连接超时，请检查后端服务是否启动"
                    e.message?.contains("Failed to connect") == true -> "无法连接后端，请检查服务器地址"
                    else -> "模型配置同步失败: ${e.message}"
                }
                showToast(errorMsg)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Model config sync failed: ${response.code} ${response.message}")
                        showToast("模型配置同步失败")
                    } else {
                        Log.d(TAG, "Model config synced successfully")
                        showToast("模型配置已保存并同步到后台")
                    }
                }
            }
        })
    }

    private fun fetchManagerModelFromBackend() {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) return

        val deviceId = prefs.deviceId
        val request = Request.Builder()
            .url("$backendUrl/api/devices/$deviceId/manager-model")
            .get()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Manager model config fetch failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (response.code == 404) {
                        Log.d(TAG, "No manager model config for device, using local prefs")
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Manager model config fetch failed: ${response.code} ${response.message}")
                        return
                    }

                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return

                    val baseUrl = json.optString("base_url").trim()
                    val apiKey = json.optString("api_key").trim()
                    val model = json.optString("model").trim()

                    if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                        Log.w(TAG, "Manager model config fetch returned incomplete payload")
                        return
                    }

                    prefs.managerBaseUrl = baseUrl
                    prefs.managerApiKey = apiKey
                    prefs.managerModelName = model

                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (managerBaseUrlInput.text.isNullOrEmpty()) {
                            managerBaseUrlInput.setText(baseUrl)
                        }
                        if (managerApiKeyInput.text.isNullOrEmpty()) {
                            managerApiKeyInput.setText(apiKey)
                        }
                        if (managerModelNameInput.text.isNullOrEmpty()) {
                            managerModelNameInput.setText(model)
                        }
                    }
                }
            }
        })
    }

    private fun saveManagerModelConfig() {
        val baseUrl = managerBaseUrlInput.text?.toString()?.trim()
        val apiKey = managerApiKeyInput.text?.toString()?.trim()
        val model = managerModelNameInput.text?.toString()?.trim()

        managerBaseUrlLayout.error = null
        managerApiKeyLayout.error = null
        managerModelNameLayout.error = null

        // 允许清空所有字段(删除配置),但不允许部分填写
        val hasAnyField = !baseUrl.isNullOrEmpty() || !apiKey.isNullOrEmpty() || !model.isNullOrEmpty()
        if (hasAnyField) {
            // 如果填写了任何字段,则必须全部填写
            if (baseUrl.isNullOrEmpty()) {
                managerBaseUrlLayout.error = getString(R.string.error_manager_base_url_required)
                return
            }
            if (apiKey.isNullOrEmpty()) {
                managerApiKeyLayout.error = getString(R.string.error_manager_api_key_required)
                return
            }
            if (model.isNullOrEmpty()) {
                managerModelNameLayout.error = getString(R.string.error_manager_model_name_required)
                return
            }
        }

        // 保存到本地 (允许 null 表示清除)
        prefs.managerBaseUrl = baseUrl?.takeIf { it.isNotEmpty() }
        prefs.managerApiKey = apiKey?.takeIf { it.isNotEmpty() }
        prefs.managerModelName = model?.takeIf { it.isNotEmpty() }

        syncManagerModelToBackend(prefs.deviceId, baseUrl, apiKey, model)
    }

    private fun syncManagerModelToBackend(deviceId: String, baseUrl: String?, apiKey: String?, model: String?) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')
        if (backendUrl.isEmpty()) {
            Log.w(TAG, "Backend URL missing, skip manager model config sync")
            showToast(getString(R.string.error_backend_url_empty))
            return
        }

        // 如果所有字段为空,发送 DELETE 请求删除配置
        if (baseUrl.isNullOrEmpty() && apiKey.isNullOrEmpty() && model.isNullOrEmpty()) {
            deleteManagerModelFromBackend(deviceId, backendUrl)
            return
        }

        val payload = JSONObject().apply {
            put("provider", "openai")
            put("base_url", baseUrl)
            put("api_key", apiKey)
            put("model", model)
        }

        val request = Request.Builder()
            .url("$backendUrl/api/devices/$deviceId/manager-model")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Manager model config sync failed", e)
                val errorMsg = when {
                    e is java.net.SocketTimeoutException -> getString(R.string.error_connection_timeout)
                    e.message?.contains("Failed to connect") == true -> getString(R.string.error_connection_failed, "")
                    else -> getString(R.string.manager_sync_failed)
                }
                showToast(errorMsg)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Manager model config sync failed: ${response.code} ${response.message}")
                        showToast(getString(R.string.manager_sync_failed))
                    } else {
                        Log.d(TAG, "Manager model config synced successfully")
                        showToast(getString(R.string.manager_model_saved))
                    }
                }
            }
        })
    }

    private fun deleteManagerModelFromBackend(deviceId: String, backendUrl: String) {
        val request = Request.Builder()
            .url("$backendUrl/api/devices/$deviceId/manager-model")
            .delete()
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Manager model delete request failed, config cleared locally", e)
                showToast(getString(R.string.manager_model_cleared_local))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 401 || response.code == 403) {
                        handleUnauthorized()
                        return
                    }
                    // 404 也是成功(已经不存在了)
                    if (response.isSuccessful || response.code == 404) {
                        Log.d(TAG, "Manager model config deleted successfully")
                        showToast(getString(R.string.manager_model_cleared))
                    } else {
                        Log.w(TAG, "Manager model delete failed: ${response.code}, config cleared locally")
                        showToast(getString(R.string.manager_model_cleared_local))
                    }
                }
            }
        })
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }
}
