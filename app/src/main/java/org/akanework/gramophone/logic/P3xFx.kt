package org.akanework.gramophone.logic

import android.view.View
import android.view.animation.OvershootInterpolator

/** The one tiny animation buttons get when their state flips. */
fun View.p3xPop() {
    animate().cancel()
    scaleX = 0.8f
    scaleY = 0.8f
    animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.2f)).start()
}

/** +20% color saturation. */
fun p3xBoost(color: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
    return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
}
