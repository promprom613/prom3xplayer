package org.akanework.gramophone.logic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.preference.PreferenceManager

/**
 * Short "tick" for song change / play / pause.
 * UI (screen on) uses the touch-feedback vibration category, which follows the normal
 * "touch vibration" setting. The playback service only ticks when no UI is visible.
 */
object Haptics {
    @Volatile
    var uiActive = false

    @Suppress("DEPRECATION")
    fun tick(ctx: Context, background: Boolean = false) {
        try {
            val app = ctx.applicationContext
            if (!PreferenceManager.getDefaultSharedPreferences(app)
                    .getBoolean("haptic_tick", true)
            ) return
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (vib.hasAmplitudeControl()) 200 else VibrationEffect.DEFAULT_AMPLITUDE
                val effect = VibrationEffect.createOneShot(28L, amp)
                if (Build.VERSION.SDK_INT >= 33) {
                    val usage = if (background) VibrationAttributes.USAGE_NOTIFICATION
                    else VibrationAttributes.USAGE_TOUCH
                    vib.vibrate(effect, VibrationAttributes.createForUsage(usage))
                } else {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(
                            if (background) AudioAttributes.USAGE_NOTIFICATION
                            else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vib.vibrate(effect, attrs)
                }
            } else {
                vib.vibrate(28L)
            }
        } catch (_: Exception) {
            // haptics are best effort only
        }
    }
}
