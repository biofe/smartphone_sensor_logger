package io.github.biofe.sensorlogger

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A short buzz confirms a poll tap without looking at the screen. */
object Haptics {
    fun tap(context: Context) = buzz(context, VibrationEffect.EFFECT_CLICK)

    fun undo(context: Context) = buzz(context, VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun buzz(context: Context, effect: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createPredefined(effect))
    }
}
