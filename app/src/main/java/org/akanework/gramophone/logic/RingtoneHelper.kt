package org.akanework.gramophone.logic

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.media3.common.MediaItem
import org.akanework.gramophone.R

/** Sets a song of the library as the phone ringtone or notification sound. */
object RingtoneHelper {
    /** [type] is [RingtoneManager.TYPE_RINGTONE] or [RingtoneManager.TYPE_NOTIFICATION]. */
    fun set(activity: Activity, item: MediaItem, type: Int) {
        if (!Settings.System.canWrite(activity)) {
            P3xToast.makeText(activity, R.string.p3x_need_write_settings, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (e: Exception) {
                // no settings screen available, nothing else to do
            }
            return
        }
        val title = item.mediaMetadata.title?.toString() ?: ""
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        if (uri == null) {
            P3xToast.makeText(
                activity, activity.getString(R.string.p3x_sound_failed, title), Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            RingtoneManager.setActualDefaultRingtoneUri(activity, type, uri)
            P3xToast.makeText(
                activity,
                activity.getString(
                    if (type == RingtoneManager.TYPE_RINGTONE) R.string.p3x_ringtone_done
                    else R.string.p3x_notification_done, title
                ),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            P3xToast.makeText(
                activity, activity.getString(R.string.p3x_sound_failed, title), Toast.LENGTH_LONG
            ).show()
        }
    }
}
