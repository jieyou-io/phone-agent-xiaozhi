package com.xiaozhi.phoneagent.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val context: Context = context.applicationContext

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var modelName: String?
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME)
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

    // Manager/Planner Model Settings (optional, for intelligent planning)
    var managerApiKey: String?
        get() = prefs.getString(KEY_MANAGER_API_KEY, null)
        set(value) = prefs.edit { putString(KEY_MANAGER_API_KEY, value) }

    var managerBaseUrl: String?
        get() = prefs.getString(KEY_MANAGER_BASE_URL, null)
        set(value) = prefs.edit { putString(KEY_MANAGER_BASE_URL, value) }

    var managerModelName: String?
        get() = prefs.getString(KEY_MANAGER_MODEL_NAME, null)
        set(value) = prefs.edit { putString(KEY_MANAGER_MODEL_NAME, value) }


    val backendUrl: String
        get() {
            val server = DEFAULT_SERVER_ADDRESS.trim().trimEnd('/')
            return if (server.contains("://")) server else "http://$server"
        }

    val webSocketUrl: String
        get() {
            val server = DEFAULT_SERVER_ADDRESS.trim().trimEnd('/')
            val base = if (server.startsWith("http://")) {
                server.replace("http://", "ws://")
            } else if (server.startsWith("https://")) {
                server.replace("https://", "wss://")
            } else {
                "ws://$server"
            }
            return "$base/ws"
        }

    // Baidu Speech Settings
    var baiduAppId: String?
        get() = prefs.getString(KEY_BAIDU_APP_ID, null)
        set(value) = prefs.edit { putString(KEY_BAIDU_APP_ID, value) }

    var baiduApiKey: String?
        get() = prefs.getString(KEY_BAIDU_API_KEY, null)
        set(value) = prefs.edit { putString(KEY_BAIDU_API_KEY, value) }

    var baiduSecretKey: String?
        get() = prefs.getString(KEY_BAIDU_SECRET_KEY, null)
        set(value) = prefs.edit { putString(KEY_BAIDU_SECRET_KEY, value) }

    var useBaiduSpeech: Boolean
        get() = prefs.getBoolean(KEY_USE_BAIDU_SPEECH, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_BAIDU_SPEECH, value) }

    val isConfigured: Boolean
        get() = !baseUrl.isNullOrEmpty() && !modelName.isNullOrEmpty() && !apiKey.isNullOrEmpty()

    val isBaiduSpeechConfigured: Boolean
        get() = useBaiduSpeech

    var voiceAutoSend: Boolean
        get() = prefs.getBoolean(KEY_VOICE_AUTO_SEND, true) // Default true
        set(value) = prefs.edit { putBoolean(KEY_VOICE_AUTO_SEND, value) }

    var customApps: Map<String, String>
        get() {
            val json = prefs.getString(KEY_CUSTOM_APPS, "{}")
            return try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                com.google.gson.Gson().fromJson(json, type)
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit { putString(KEY_CUSTOM_APPS, json) }
        }

    var actionDelay: Int
        get() = prefs.getInt(KEY_ACTION_DELAY, 1000) // Default 1 second
        set(value) = prefs.edit { putInt(KEY_ACTION_DELAY, value) }

    var showOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_OVERLAY, true) // Default true
        set(value) = prefs.edit { putBoolean(KEY_SHOW_OVERLAY, value) }

    val deviceId: String
        get() {
            // 1. 优先使用已存储的ID(保护已有设备)
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (!stored.isNullOrBlank()) return stored

            // 2. 尝试使用Android ID (SSAID) - 应用重装后保持不变
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                android.util.Log.w("PrefsManager", "Failed to get Android ID: ${e.message}")
                null
            }

            // 验证Android ID有效性(非空、非已知无效值)
            val validAndroidId = androidId?.takeIf {
                it.isNotBlank() && !INVALID_ANDROID_IDS.contains(it)
            }

            // 3. 生成最终ID
            val finalId = if (validAndroidId != null) {
                // 使用SHA-256哈希Android ID增强隐私，并格式化为标准UUID格式
                val hash = sha256("$validAndroidId|$ANDROID_ID_SALT")
                // 格式化为UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (符合后端36字符验证)
                "${hash.substring(0, 8)}-${hash.substring(8, 12)}-${hash.substring(12, 16)}-${hash.substring(16, 20)}-${hash.substring(20, 32)}"
            } else {
                // 回退到UUID(Android ID不可用)
                android.util.Log.i("PrefsManager", "Android ID unavailable, using UUID fallback")
                UUID.randomUUID().toString()
            }

            // 4. 持久化存储
            prefs.edit { putString(KEY_DEVICE_ID, finalId) }
            return finalId
        }

    /**
     * SHA-256哈希工具函数(用于Android ID哈希)
     * 使用UTF-8编码确保跨平台一致性
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "phone_agent_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"

        // Manager/Planner Model Keys
        private const val KEY_MANAGER_API_KEY = "manager_api_key"
        private const val KEY_MANAGER_BASE_URL = "manager_base_url"
        private const val KEY_MANAGER_MODEL_NAME = "manager_model_name"

        // Baidu Speech Keys
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_API_KEY = "baidu_api_key"
        private const val KEY_BAIDU_SECRET_KEY = "baidu_secret_key"
        private const val KEY_USE_BAIDU_SPEECH = "use_baidu_speech"
        private const val KEY_VOICE_AUTO_SEND = "voice_auto_send"
        private const val KEY_CUSTOM_APPS = "custom_apps"
        private const val KEY_ACTION_DELAY = "action_delay"
        private const val KEY_SHOW_OVERLAY = "show_overlay"
        private const val KEY_DEVICE_ID = "device_id"

        /**
         * Android ID哈希盐值
         * ⚠️ 警告：修改此值会导致所有设备ID重新生成！
         */
        private const val ANDROID_ID_SALT = "xiaozhi_phone_agent_v1"

        /**
         * 已知无效的Android ID值(需要过滤)
         */
        private val INVALID_ANDROID_IDS = setOf(
            "9774d56d682e549c",  // Android 2.x bug值
            "unknown",            // 部分设备返回
            "0"                   // 部分边缘设备
        )

        const val DEFAULT_BASE_URL = ""
        const val DEFAULT_MODEL_NAME = ""
        const val DEFAULT_SERVER_ADDRESS = "192.168.123.113:8000"
    }
}
