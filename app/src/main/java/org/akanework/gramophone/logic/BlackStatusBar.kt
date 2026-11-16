package org.akanework.gramophone.logic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps the status bar black (with light icons) in every activity of the app.
 * Edge-to-edge windows ignore statusBarColor on Android 15+, so a black strip the
 * height of the status bar is drawn on top of the window as well.
 */
object BlackStatusBar {
    private const val BAR_TAG = "p3x_black_status_bar"

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) = apply(activity)
            override fun onActivityResumed(activity: Activity) = apply(activity)
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @Suppress("DEPRECATION")
    private fun apply(activity: Activity) {
        val window = activity.window
        val decor = window.decorView as? ViewGroup ?: return
        window.statusBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, decor).isAppearanceLightStatusBars = false
        for (i in 0 until decor.childCount) {
            if (decor.getChildAt(i).tag == BAR_TAG) return
        }
        decor.addView(
            Bar(activity),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP)
        )
        decor.requestApplyInsets()
    }

    private class Bar(context: Context) : View(context) {
        init {
            tag = BAR_TAG
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            val top = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
                .getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = layoutParams
            if (lp != null && lp.height != top) {
                lp.height = top
                layoutParams = lp
            }
            return insets
        }
    }
}
