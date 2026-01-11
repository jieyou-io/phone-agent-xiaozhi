package com.xiaozhi.phoneagent.effects

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class ShakeEffect(private val context: Context) {
    fun trigger(intensity: String) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val duration = if (intensity == "high") 800L else 300L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
