package com.xiaozhi.phoneagent.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.xiaozhi.phoneagent.ui.LoginActivity
import com.xiaozhi.phoneagent.utils.HttpClient
import com.xiaozhi.phoneagent.utils.PrefsManager
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class BaiduSpeechManager(private val context: Context) {
    private var listener: SpeechListener? = null
    private val prefs = PrefsManager(context)
    private val client = HttpClient.get()
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRedirectingToLogin = false

    interface SpeechListener {
        fun onReady()
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(errorCode: Int, errorMessage: String)
        fun onFinish()
    }

    fun initialize(): Boolean {
        if (!prefs.isBaiduSpeechConfigured) {
            Log.w(TAG, "百度语音未配置")
            return false
        }
        return true
    }

    fun setListener(listener: SpeechListener) {
        this.listener = listener
    }

    fun startListening() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener?.onError(-1, "Permission denied")
            return
        }

        if (isRecording) return

        isRecording = true
        listener?.onReady()
        
        recordJob = scope.launch {
            try {
                recordAndRecognize()
            } catch (e: Exception) {
                Log.e(TAG, "录音错误", e)
                withContext(Dispatchers.Main) {
                    listener?.onError(-1, e.message ?: "Unknown error")
                }
            }
        }
    }

    fun stopListening() {
        isRecording = false
    }

    fun cancel() {
        isRecording = false
        recordJob?.cancel()
    }

    fun release() {
        cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAndRecognize() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord not initialized")
        }

        recorder.startRecording()
        Log.d(TAG, "开始录音")

        val audioData = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)

        try {
            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    audioData.write(buffer, 0, read)
                    // Implement strict VAD/Silence detection here if needed
                    // For now, we rely on user manually stopping by calling stopListening()
                    // Or we could implement a simple amplitude check to auto-stop
                    
                    val maxAmplitude = buffer.take(read).map { Math.abs(it.toInt()) }.maxOrNull() ?: 0
                    if (maxAmplitude > 0) {
                         // Log.v(TAG, "Mic amplitude: $maxAmplitude")
                    }
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            Log.d(TAG, "停止录音，数据大小: ${audioData.size()}")
        }

        val pcmData = audioData.toByteArray()
        if (pcmData.isNotEmpty()) {
            recognizeAudio(pcmData)
        } else {
            withContext(Dispatchers.Main) {
                listener?.onError(2000, "No audio data") // 2000: data empty
            }
        }
        
        withContext(Dispatchers.Main) {
            listener?.onFinish()
        }
    }

    private suspend fun recognizeAudio(pcmData: ByteArray) {
        val backendUrl = prefs.backendUrl.trim().trimEnd('/')

        Log.d(TAG, "上传音频，大小: ${pcmData.size} 字节")

        if (backendUrl.isEmpty()) {
            withContext(Dispatchers.Main) {
                listener?.onError(-1, "Backend URL missing")
            }
            return
        }

        val speech = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("device_id", prefs.deviceId)
            put("audio", speech)
            put("format", "pcm")
            put("rate", 16000)
            put("channel", 1)
            put("dev_pid", 1537)
        }

        val request = Request.Builder()
            .url("$backendUrl/api/baidu-speech/recognize")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                val code = response.code
                val body = response.body?.string()
                val detail = body?.let {
                    runCatching { JSONObject(it).optString("detail") }.getOrNull()
                }
                val errorMsg = detail?.ifBlank { null } ?: "HTTP Error: $code"

                if (code == 401 || code == 403) {
                    handleAuthExpired(errorMsg)
                    return
                }

                Log.e(TAG, errorMsg)
                withContext(Dispatchers.Main) {
                    listener?.onError(code, errorMsg)
                }
                return
            }

            val responseBody = response.body?.string().orEmpty()
            Log.d(TAG, "后端 ASR 响应: $responseBody")

            val jsonResponse = JSONObject(responseBody)
            val result = jsonResponse.optString("result")

            withContext(Dispatchers.Main) {
                if (!result.isNullOrEmpty()) {
                    listener?.onResult(result)
                } else {
                    listener?.onError(0, "Empty result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "网络错误", e)
            val errorMsg = when {
                e is java.net.SocketTimeoutException -> "连接超时，请检查后端服务是否启动"
                e.message?.contains("Failed to connect") == true -> "无法连接后端，请检查服务器地址"
                else -> "Network error: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                listener?.onError(-1, errorMsg)
            }
        }
    }

    private suspend fun handleAuthExpired(message: String) {
        if (isRedirectingToLogin) {
            Log.d(TAG, "已在跳转登录，跳过重复操作")
            return
        }
        isRedirectingToLogin = true
        Log.w(TAG, "语音识别期间会话过期，跳转到登录")
        HttpClient.clearCookies()
        withContext(Dispatchers.Main) {
            listener?.onError(401, message)
            val intent = Intent(context, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    companion object {
        private const val TAG = "BaiduSpeechManager"
        private const val SAMPLE_RATE = 16000
    }
}
