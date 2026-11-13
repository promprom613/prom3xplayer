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

import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.components.TabOrderPreference
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

class AppearanceSettingsActivity : BaseSettingsActivity(
    R.string.settings_category_appearance,
    { AppearanceSettingsFragment() })

class AppearanceSettingsFragment : BasePreferenceFragment() {
    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null || !isAdded) return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
            preferenceManager.sharedPreferences?.edit()
                ?.putString("main_bg_uri", uri.toString())?.apply()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_appearance, rootKey)
        findPreference<Preference>("main_bg_pick")?.setOnPreferenceClickListener {
            try {
                pickBackground.launch(arrayOf("image/*"))
            } catch (_: Throwable) {
            }
            true
        }
        findPreference<Preference>("main_bg_clear")?.setOnPreferenceClickListener {
            preferenceManager.sharedPreferences?.edit()?.remove("main_bg_uri")?.apply()
            true
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "theme_mode" -> {
                when (sharedPreferences?.getString("theme_mode", "0")) {
                    "0" -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }

                    "1" -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }

                    "2" -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                }
            }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is TabOrderPreference) {
            val f = TabOrderPreference.TabOrderDialog.newInstance(preference.key)
            @Suppress("deprecation") // original class does it like this as well
            f.setTargetFragment(this, 0)
            f.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG")
        } else super.onDisplayPreferenceDialog(preference)
    }

}
