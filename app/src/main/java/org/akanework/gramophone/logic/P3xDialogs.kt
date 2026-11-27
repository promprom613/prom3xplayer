package org.akanework.gramophone.logic

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.app.AlertDialog

/** Dialog surface is always AMOLED black (instead of the default grey). */
fun AlertDialog.p3xBlack(): AlertDialog {
    val d = context.resources.displayMetrics.density
    val shape = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 28 * d
    }
    window?.setBackgroundDrawable(InsetDrawable(shape, (16 * d).toInt()))
    return this
}
