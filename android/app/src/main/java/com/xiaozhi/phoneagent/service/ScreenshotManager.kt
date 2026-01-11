package com.xiaozhi.phoneagent.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class ScreenshotManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    val isReady: Boolean
        get() = mediaProjection != null

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    fun initialize(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Register callback for Android 14+ requirement
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                release()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Log.d(TAG, "截图管理器已初始化: ${screenWidth}x${screenHeight}")
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isReady || imageReader == null) {
            Log.e(TAG, "截图管理器未就绪")
            return@withContext null
        }

        suspendCancellableCoroutine { continuation ->
            try {
                // Drain any old images to ensure we get the absolute latest frame
                // ImageReader with buffer size 2 can store a stale frame.
                var latestImage: android.media.Image? = null
                while (true) {
                    val img = imageReader?.acquireLatestImage() ?: break
                    latestImage?.close()
                    latestImage = img
                }

                if (latestImage == null) {
                    Log.w(TAG, "初次获取无图像，等待 150ms...")
                    Thread.sleep(150)
                    latestImage = imageReader?.acquireLatestImage()
                }

                if (latestImage == null) {
                    Log.e(TAG, "等待后仍无图像可用")
                    continuation.resume(null)
                } else {
                    val bitmap = imageToBitmap(latestImage)
                    latestImage.close()
                    continuation.resume(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "截图失败", e)
                continuation.resume(null)
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        Log.d(TAG, "截图管理器已释放")
    }

    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    fun saveScreenshot(bitmap: Bitmap, stepNumber: Int): String? {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "step_${stepNumber}_$timestamp.png"
            val file = File(dir, filename)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Log.d(TAG, "截图已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败", e)
            null
        }
    }

    companion object {
        private const val TAG = "ScreenshotManager"

        fun createCaptureIntent(activity: Activity): Intent {
            val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return projectionManager.createScreenCaptureIntent()
        }
    }
}
