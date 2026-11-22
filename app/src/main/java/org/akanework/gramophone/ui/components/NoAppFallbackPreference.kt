/*
 *     Copyright (C) 2025 nift4
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

package org.akanework.gramophone.ui.components

import org.akanework.gramophone.logic.P3xToast

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import org.akanework.gramophone.R

class NoAppFallbackPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    init {
        onPreferenceClickListener = OnPreferenceClickListener { _ ->
            intent?.let {
                try {
                    context.startActivity(it)
                } catch (_: ActivityNotFoundException) {
                    P3xToast.makeText(context, R.string.no_app_found, Toast.LENGTH_LONG).show()
                }
            } != null
        }
    }
}