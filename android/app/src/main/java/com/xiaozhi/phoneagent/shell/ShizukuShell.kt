package com.xiaozhi.phoneagent.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader

object ShizukuShell {
    private const val TAG = "ShizukuShell"

    val isAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

    val hasPermission: Boolean
        get() = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permission", e)
        }
    }

    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext ShellResult(false, "", "Shizuku not available")
        }
        if (!hasPermission) {
            return@withContext ShellResult(false, "", "Shizuku permission denied")
        }

        try {
            // Use Shizuku remote process via reflection for compatibility
            val process = createShizukuProcess(arrayOf("sh", "-c", command))

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()

            process.destroy()

            ShellResult(
                success = exitCode == 0,
                output = stdout.trim(),
                error = stderr.trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution failed", e)
            ShellResult(false, "", e.message ?: "Unknown error")
        }
    }

    private fun createShizukuProcess(command: Array<String>): Process {
        // Try different methods to create process with Shizuku
        return try {
            // Method 1: Use Shizuku.newProcess via reflection
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, command, null, null) as Process
        } catch (e: Exception) {
            Log.w(TAG, "Reflection method failed, trying alternative", e)
            // Method 2: Fallback - this won't have elevated permissions but prevents crash
            Runtime.getRuntime().exec(command)
        }
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        val result = execute("input tap $x $y")
        kotlinx.coroutines.delay(1000) // Match Python TIMING_CONFIG.device.default_tap_delay
        return result.success
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): Boolean {
        val result = execute("input swipe $x1 $y1 $x2 $y2 $duration")
        kotlinx.coroutines.delay(1000) // Match Python TIMING_CONFIG.device.default_swipe_delay
        return result.success
    }

    suspend fun inputText(text: String): Boolean {
        // Handle multiline text by splitting and sending each line separately
        if (text.contains('\n')) {
            val lines = text.split('\n')
            for (i in lines.indices) {
                if (lines[i].isNotEmpty()) {
                    if (!inputText(lines[i])) return false
                }
                // Press enter after each line except the last one
                if (i < lines.size - 1) {
                    if (!keyEvent(66)) return false // KEYCODE_ENTER = 66
                }
            }
            return true
        }

        // Save current IME
        val getCurrentImeResult = execute("settings get secure default_input_method")
        val currentIme = if (getCurrentImeResult.success) {
            getCurrentImeResult.output.trim()
        } else {
            Log.w(TAG, "Failed to get current IME")
            null
        }
        
        Log.d(TAG, "Current IME: $currentIme")
        
        // Switch to ADB Keyboard (matching Python's detect_and_set_adb_keyboard)
        val adbKeyboardIme = "com.android.adbkeyboard/.AdbIME"
        if (currentIme != adbKeyboardIme) {
            Log.d(TAG, "Switching to ADB Keyboard")
            val switchResult = execute("ime set $adbKeyboardIme")
            if (!switchResult.success) {
                Log.e(TAG, "Failed to switch to ADB Keyboard: ${switchResult.error}")
                return false
            }
            // Wait for IME to switch (Match Python's keyboard_switch_delay)
            kotlinx.coroutines.delay(1000)
        }

        // Clear existing text (matching Python's clear_text)
        Log.d(TAG, "Clearing existing text")
        execute("am broadcast -a ADB_CLEAR_TEXT")
        kotlinx.coroutines.delay(1000) // Match Python's text_clear_delay

        // Use base64 encoding for better Unicode support (matching Python's type_text with ADB_INPUT_B64)
        val encodedText = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        
        val command = "am broadcast -a ADB_INPUT_B64 --es msg $encodedText"
        Log.d(TAG, "Executing input command: $command")
        
        val result = execute(command)
        kotlinx.coroutines.delay(1000) // Match Python's text_input_delay
        
        Log.d(TAG, "Input result - success: ${result.success}, output: ${result.output}, error: ${result.error}")
        
        // Switch back to original IME if we changed it (matching Python's restore_keyboard)
        if (currentIme != null && currentIme != adbKeyboardIme) {
            Log.d(TAG, "Switching back to original IME: $currentIme")
            kotlinx.coroutines.delay(1000) // Match Python's keyboard_restore_delay
            execute("ime set $currentIme")
        }
        
        return result.success
    }

    suspend fun keyEvent(keyCode: Int): Boolean {
        val result = execute("input keyevent $keyCode")
        return result.success
    }

    suspend fun back(): Boolean {
        val success = keyEvent(4)
        kotlinx.coroutines.delay(1000) // Match Python TIMING_CONFIG.device.default_back_delay
        return success
    }

    suspend fun home(): Boolean {
        val success = keyEvent(3)
        kotlinx.coroutines.delay(1000) // Match Python TIMING_CONFIG.device.default_home_delay
        return success
    }

    suspend fun launchApp(packageName: String): Boolean {
        val result = execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        kotlinx.coroutines.delay(1000) // Match Python TIMING_CONFIG.device.default_launch_delay
        return result.success
    }

    suspend fun getCurrentPackage(): String? {
        // Use dumpsys window to find focus, matching Python's get_current_app logic
        val result = execute("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (!result.success) return null

        // Try to find package name like "com.example.app" in lines like "mCurrentFocus=Window{... com.example.app/...}"
        val regex = Regex("([\\w.]+)/")
        val output = result.output
        
        // Priority to lines containing mCurrentFocus
        output.split("\n").forEach { line ->
            if (line.contains("mCurrentFocus")) {
                regex.find(line)?.groupValues?.get(1)?.let { return it }
            }
        }
        
        // Fallback to any line containing a package pattern
        return regex.find(output)?.groupValues?.get(1)
    }

    suspend fun captureScreen(): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        if (!isAvailable || !hasPermission) {
            Log.e(TAG, "Shizuku not available or permission denied for captureScreen")
            return@withContext null
        }

        try {
            // Using screencap -p for raw PNG stream, matching Python's adb shell screencap
            val process = createShizukuProcess(arrayOf("screencap", "-p"))
            val bitmap = android.graphics.BitmapFactory.decodeStream(process.inputStream)
            
            // Wait for process to exit
            process.waitFor()
            process.destroy()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from screencap output")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen via Shizuku", e)
            null
        }
    }
}

data class ShellResult(
    val success: Boolean,
    val output: String,
    val error: String
)
