package org.akanework.gramophone.logic

import android.app.Application
import android.os.Process
import org.akanework.gramophone.R
import org.xmlpull.v1.XmlPullParser

/**
 * The settings screens are not part of the app's compiled startup profile, so the first visit
 * loads, verifies and runs all of that code cold and stutters. This warms it up on a background
 * thread shortly after the app has started, so opening Settings is smooth the first time too.
 */
object SettingsPrewarm {
    private var started = false

    private val classes = listOf(
        "androidx.preference.PreferenceFragmentCompat",
        "androidx.preference.PreferenceManager",
        "androidx.preference.PreferenceInflater",
        "androidx.preference.PreferenceGroupAdapter",
        "androidx.preference.PreferenceViewHolder",
        "androidx.preference.PreferenceScreen",
        "androidx.preference.PreferenceCategory",
        "androidx.preference.SwitchPreferenceCompat",
        "androidx.preference.ListPreference",
        "androidx.preference.SeekBarPreference",
        "androidx.preference.DialogPreference",
        "com.google.android.material.card.MaterialCardView",
        "com.google.android.material.appbar.CollapsingToolbarLayout",
        "org.akanework.gramophone.ui.fragments.settings.MainSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.AppearanceSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.PlayerSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.BehaviorSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.AudioSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.AboutSettingsFragment",
        "org.akanework.gramophone.ui.fragments.settings.MainSettingsActivity",
        "org.akanework.gramophone.ui.fragments.settings.AppearanceSettingsActivity",
        "org.akanework.gramophone.ui.fragments.settings.PlayerSettingsActivity",
        "org.akanework.gramophone.ui.fragments.settings.BehaviorSettingsActivity",
        "org.akanework.gramophone.ui.fragments.settings.AudioSettingsActivity",
        "org.akanework.gramophone.ui.fragments.settings.AboutSettingsActivity",
        "org.akanework.gramophone.ui.fragments.BasePreferenceFragment",
        "org.akanework.gramophone.ui.fragments.BaseSettingsActivity",
        "org.akanework.gramophone.ui.components.NoAppFallbackPreference"
    )

    private val xmls = intArrayOf(
        R.xml.settings_top, R.xml.settings_appearance, R.xml.settings_player,
        R.xml.settings_behavior, R.xml.settings_audio, R.xml.settings_about
    )

    fun start(app: Application) {
        if (started) return
        started = true
        Thread({
            try {
                Thread.sleep(2500)
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val loader = app.classLoader
                for (name in classes) {
                    try {
                        Class.forName(name, true, loader)
                    } catch (e: Throwable) {
                        // class missing or not loadable here, not important
                    }
                }
                P3xArtCache.trim(app)
                for (id in xmls) {
                    try {
                        val parser = app.resources.getXml(id)
                        try {
                            while (parser.next() != XmlPullParser.END_DOCUMENT) { /* just read it */ }
                        } finally {
                            parser.close()
                        }
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
            } catch (e: Throwable) {
                // warm-up is best effort only
            }
        }, "p3x-settings-prewarm").apply { isDaemon = true }.start()
    }
}
