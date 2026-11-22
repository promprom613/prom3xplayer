package org.akanework.gramophone.logic

import android.content.Context

/** Toasts are switched off app-wide: same call shape as Toast, does nothing. */
class P3xToast {
    fun show() {}
    fun cancel() {}

    companion object {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun makeText(context: Context?, text: CharSequence?, duration: Int) = P3xToast()

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun makeText(context: Context?, resId: Int, duration: Int) = P3xToast()
    }
}
