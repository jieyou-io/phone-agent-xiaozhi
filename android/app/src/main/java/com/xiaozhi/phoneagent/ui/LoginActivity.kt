package com.xiaozhi.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xiaozhi.phoneagent.R
import com.xiaozhi.phoneagent.databinding.ActivityLoginBinding
import com.xiaozhi.phoneagent.utils.HttpClient
import com.xiaozhi.phoneagent.utils.PrefsManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.loginButton.setOnClickListener {
            performLogin()
        }
    }

    private fun performLogin() {
        val username = binding.usernameInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_credentials_required), Toast.LENGTH_SHORT).show()
            return
        }

        val serverInput = PrefsManager.DEFAULT_SERVER_ADDRESS
        val normalizedServer = normalizeServerAddress(serverInput)
        Log.d(TAG, "Normalized server: $serverInput -> $normalizedServer")

        binding.loadingProgress.visibility = View.VISIBLE
        binding.loginButton.isEnabled = false

        val url = "http://$normalizedServer/api/auth/login"

        val prefs = PrefsManager(this)
        val json = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("device_id", prefs.deviceId)
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Login failed", e)
                runOnUiThread {
                    binding.loadingProgress.visibility = View.INVISIBLE
                    binding.loginButton.isEnabled = true
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.error_connection_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string().orEmpty()
                        val loginJson = try {
                            JSONObject(body)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse login response", e)
                            runOnUiThread {
                                binding.loadingProgress.visibility = View.INVISIBLE
                                binding.loginButton.isEnabled = true
                                binding.loginButton.text = getString(R.string.login_button)
                                Toast.makeText(this@LoginActivity, "登录响应解析失败", Toast.LENGTH_LONG).show()
                            }
                            return
                        }

                        val deviceRegistrationRequired = loginJson.optBoolean("device_registration_required", false)
                        Log.d(TAG, "Login successful, device_registration_required: $deviceRegistrationRequired")

                        if (deviceRegistrationRequired) {
                            runOnUiThread {
                                binding.loginButton.text = "正在注册设备..."
                            }
                            registerDevice(normalizedServer)
                        } else {
                            runOnUiThread {
                                binding.loadingProgress.visibility = View.INVISIBLE
                                binding.loginButton.isEnabled = true
                                binding.loginButton.text = getString(R.string.login_button)
                                Toast.makeText(this@LoginActivity, getString(R.string.login_success), Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        }
                    } else {
                        val errorMsg = try {
                            val body = it.body?.string()
                            val errorJson = JSONObject(body ?: "{}")
                            errorJson.optString("detail", getString(R.string.error_login_failed))
                        } catch (e: Exception) {
                            getString(R.string.error_login_failed)
                        }

                        Log.e(TAG, "Login failed: ${it.code} - $errorMsg")
                        runOnUiThread {
                            binding.loadingProgress.visibility = View.INVISIBLE
                            binding.loginButton.isEnabled = true
                            binding.loginButton.text = getString(R.string.login_button)
                            Toast.makeText(
                                this@LoginActivity,
                                errorMsg,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun registerDevice(normalizedServer: String) {
        val prefs = PrefsManager(this)
        val url = "http://$normalizedServer/api/devices"

        val json = JSONObject().apply {
            put("device_id", prefs.deviceId)
            put("model", android.os.Build.MODEL)
            put("os_version", android.os.Build.VERSION.RELEASE)
            put("app_version", packageManager.getPackageInfo(packageName, 0).versionName)
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        HttpClient.get().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Device registration failed", e)
                runOnUiThread {
                    binding.loadingProgress.visibility = View.INVISIBLE
                    binding.loginButton.isEnabled = true
                    binding.loginButton.text = getString(R.string.login_button)
                    Toast.makeText(
                        this@LoginActivity,
                        "设备注册失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "Device registered successfully")
                        runOnUiThread {
                            binding.loadingProgress.visibility = View.INVISIBLE
                            binding.loginButton.isEnabled = true
                            binding.loginButton.text = getString(R.string.login_button)
                            Toast.makeText(this@LoginActivity, getString(R.string.login_success), Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        val errorMsg = try {
                            val body = it.body?.string()
                            val errorJson = JSONObject(body ?: "{}")
                            errorJson.optString("detail", "设备注册失败")
                        } catch (e: Exception) {
                            "设备注册失败"
                        }

                        Log.e(TAG, "Device registration failed: ${it.code} - $errorMsg")
                        runOnUiThread {
                            binding.loadingProgress.visibility = View.INVISIBLE
                            binding.loginButton.isEnabled = true
                            binding.loginButton.text = getString(R.string.login_button)
                            Toast.makeText(
                                this@LoginActivity,
                                errorMsg,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun normalizeServerAddress(input: String): String {
        var server = input.trim()

        // Remove protocol
        server = server.removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")

        // Remove path suffixes
        server = server.removeSuffix("/ws/")
            .removeSuffix("/ws")
            .removeSuffix("/api")
            .removeSuffix("/")

        // Remove query and fragment
        if (server.contains("?")) server = server.substringBefore("?")
        if (server.contains("#")) server = server.substringBefore("#")

        return server
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
