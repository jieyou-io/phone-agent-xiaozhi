package com.xiaozhi.phoneagent.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.xiaozhi.phoneagent.utils.PrefsManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BaiduSpeechManager(private val context: Context) {
    private var listener: SpeechListener? = null
    private val prefs = PrefsManager(context)
    private val client = OkHttpClient()
    private val gson = Gson()
    private var isRecording = false
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accessToken: String? = null
    private var tokenExpireTime: Long = 0

    interface SpeechListener {
        fun onReady()
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(errorCode: Int, errorMessage: String)
        fun onFinish()
    }

    fun initialize(): Boolean {
        if (!prefs.isBaiduSpeechConfigured) {
            Log.w(TAG, "Baidu Speech not configured")
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
                Log.e(TAG, "Recording error", e)
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
        Log.d(TAG, "Started recording")

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
            Log.d(TAG, "Stopped recording, data size: ${audioData.size()}")
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
        val token = getAccessToken() ?: return
        
        Log.d(TAG, "Uploading audio, size: ${pcmData.size} bytes")

        val speech = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("format", "pcm")
            put("rate", 16000)
            put("dev_pid", 1537)
            put("channel", 1)
            put("token", token)
            put("cuid", "com.xiaozhi.phoneagent")
            put("len", pcmData.size)
            put("speech", speech)
        }

        val request = Request.Builder()
            .url(ASR_URL)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                val errorMsg = "HTTP Error: ${response.code}"
                Log.e(TAG, errorMsg)
                withContext(Dispatchers.Main) {
                    listener?.onError(response.code, errorMsg)
                }
                return
            }

            val responseBody = response.body?.string()
            Log.d(TAG, "ASR Response: $responseBody")

            if (responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val errNo = jsonResponse.optInt("err_no", -1)
                
                withContext(Dispatchers.Main) {
                    if (errNo == 0) {
                        val result = jsonResponse.optJSONArray("result")?.optString(0)
                        if (!result.isNullOrEmpty()) {
                            listener?.onResult(result)
                        } else {
                            listener?.onError(0, "Empty result")
                        }
                    } else {
                        val errMsg = jsonResponse.optString("err_msg", "Unknown error")
                        listener?.onError(errNo, errMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error", e)
            withContext(Dispatchers.Main) {
                listener?.onError(-1, "Network error: ${e.message}")
            }
        }
    }

    private suspend fun getAccessToken(): String? {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken
        }

        val apiKey = prefs.baiduApiKey
        val secretKey = prefs.baiduSecretKey
        
        if (apiKey.isNullOrEmpty() || secretKey.isNullOrEmpty()) {
            withContext(Dispatchers.Main) {
                listener?.onError(-1, "API Key or Secret missing")
            }
            return null
        }

        val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val request = Request.Builder().url(url).build()

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                if (json.has("access_token")) {
                    accessToken = json.getString("access_token")
                    val expiresIn = json.optLong("expires_in", 2592000) // Default 30 days
                    tokenExpireTime = System.currentTimeMillis() + (expiresIn * 1000) - 60000 // Buffer 1 min
                    Log.d(TAG, "Token refreshed")
                    accessToken
                } else {
                    Log.e(TAG, "Token error: $body")
                    withContext(Dispatchers.Main) {
                        listener?.onError(-1, "Auth failed: $body")
                    }
                    null
                }
            } else {
                Log.e(TAG, "Token HTTP error: ${response.code}")
                 withContext(Dispatchers.Main) {
                    listener?.onError(response.code, "Token HTTP error")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token network error", e)
             withContext(Dispatchers.Main) {
                listener?.onError(-1, "Token network error: ${e.message}")
            }
            null
        }
    }

    companion object {
        private const val TAG = "BaiduSpeechManager"
        private const val SAMPLE_RATE = 16000
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val ASR_URL = "http://vop.baidu.com/server_api"
    }
}
