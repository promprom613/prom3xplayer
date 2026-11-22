/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.fragments.settings

import org.akanework.gramophone.logic.P3xToast

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.gramophoneApplication
import org.akanework.gramophone.logic.hasImagePermission
import org.akanework.gramophone.logic.hasScopedStorageWithMediaTypes
import org.akanework.gramophone.logic.utils.Flags
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity


class BehaviorSettingsActivity : BaseSettingsActivity(
    R.string.settings_category_behavior,
    { BehaviorSettingsFragment() })

class BehaviorSettingsFragment : BasePreferenceFragment() {

    override fun onResume() {
        super.onResume()
        if (hasScopedStorageWithMediaTypes()) {
            val preference = findPreference<SwitchPreferenceCompat>("album_covers")!!
            preference.isPersistent = false
            if (!Flags.REMOVE_IMAGE_PERMISSION)
                preference.isChecked = requireContext().hasImagePermission()
            else
                preference.isVisible = false
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
    }

    /** Shows the library as a list and sets the chosen song as ringtone / notification sound. */
    private fun p3xPickSong(type: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = requireContext().gramophoneApplication.reader.songListFlow.first()
                .sortedBy { it.mediaMetadata.title?.toString()?.lowercase() ?: "" }
            if (songs.isEmpty()) return@launch
            val names = songs.map {
                val artist = it.mediaMetadata.artist?.toString()
                (it.mediaMetadata.title?.toString() ?: "?") + if (artist.isNullOrEmpty()) "" else " - $artist"
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.p3x_pick_song)
                .setItems(names) { _, i ->
                    org.akanework.gramophone.logic.RingtoneHelper.set(requireActivity(), songs[i], type)
                }
                .show()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "blacklist") {
            startActivity(BlacklistSettingsActivity::class.java)
        }
        if (preference.key == "p3x_ringtone") {
            p3xPickSong(android.media.RingtoneManager.TYPE_RINGTONE)
            return true
        }
        if (preference.key == "p3x_notification") {
            p3xPickSong(android.media.RingtoneManager.TYPE_NOTIFICATION)
            return true
        }
        // Prior to Android 13, this changes a setting which changes MediaStoreUtils behaviour
        // Android 13 and later, this displays state of images permission granted/denied
        if (hasScopedStorageWithMediaTypes() && !Flags.REMOVE_IMAGE_PERMISSION &&
            preference.key == "album_covers") {
            P3xToast.makeText(
                requireActivity(), if (requireContext().hasImagePermission())
                    R.string.deny_images else R.string.grant_images, Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData("package:${requireContext().packageName}".toUri())
            startActivity(intent)
        }
        return super.onPreferenceTreeClick(preference)
    }
}
