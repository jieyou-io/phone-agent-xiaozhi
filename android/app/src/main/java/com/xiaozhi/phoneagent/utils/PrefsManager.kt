package com.xiaozhi.phoneagent.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
        set(value) = prefs.edit { putString(KEY_BASE_URL, value) }

    var modelName: String?
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME)
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

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
        get() = !apiKey.isNullOrEmpty()

    val isBaiduSpeechConfigured: Boolean
        get() = useBaiduSpeech && !baiduAppId.isNullOrEmpty() && !baiduApiKey.isNullOrEmpty() && !baiduSecretKey.isNullOrEmpty()

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

    companion object {
        private const val PREFS_NAME = "phone_agent_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"

        // Baidu Speech Keys
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_API_KEY = "baidu_api_key"
        private const val KEY_BAIDU_SECRET_KEY = "baidu_secret_key"
        private const val KEY_USE_BAIDU_SPEECH = "use_baidu_speech"
        private const val KEY_VOICE_AUTO_SEND = "voice_auto_send"
        private const val KEY_CUSTOM_APPS = "custom_apps"
        private const val KEY_ACTION_DELAY = "action_delay"
        private const val KEY_SHOW_OVERLAY = "show_overlay"

        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL_NAME = "autoglm-phone"

        const val DEFAULT_API_KEY= ""
    }
}
