package org.akanework.gramophone.ui.components

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.color.MaterialColors
import org.akanework.gramophone.R

/**
 * Floating vertical pill that replaces the tab row: one icon per library tab plus a power button.
 */
class TabRail @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    class Entry(val icon: Int, val label: Int)

    var onSelect: ((Int) -> Unit)? = null
    var onPower: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val buttonSize = (40 * d).toInt()
    private val pad = (9 * d).toInt()
    private val tabButtons = ArrayList<ImageView>()
    private val circles = ArrayList<GradientDrawable>()
    private var selected = -1
    private val idleTint = 0xFFC8C8C8.toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (6 * d).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(0xC7101010.toInt())
            cornerRadius = 200 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), 0x26FFFFFF)
        }
        clipToPadding = false
    }

    private fun accent() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
    private fun onAccent() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer)

    fun setTabs(entries: List<Entry>) {
        removeAllViews()
        tabButtons.clear()
        circles.clear()
        selected = -1
        if (entries.size >= 2) {
            entries.forEachIndexed { i, e ->
                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent())
                    alpha = 0
                }
                val b = ImageView(context).apply {
                    setImageResource(e.icon)
                    setPadding(pad, pad, pad, pad)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.LayerDrawable(arrayOf(circle))
                    imageTintList = ColorStateList.valueOf(idleTint)
                    contentDescription = context.getString(e.label)
                    TooltipCompat.setTooltipText(this, context.getString(e.label))
                    isClickable = true
                    setOnClickListener { onSelect?.invoke(i) }
                }
                tabButtons.add(b)
                circles.add(circle)
                addView(b, LayoutParams(buttonSize, buttonSize))
            }
            addView(View(context).apply { setBackgroundColor(0x33FFFFFF) },
                LayoutParams((22 * d).toInt(), (1 * d).toInt().coerceAtLeast(1)).apply {
                    topMargin = (4 * d).toInt(); bottomMargin = (4 * d).toInt()
                })
        }
        val power = ImageView(context).apply {
            setImageResource(R.drawable.p3x_power)
            setPadding(pad, pad, pad, pad)
            imageTintList = ColorStateList.valueOf(0xFFFF6B6B.toInt())
            contentDescription = context.getString(R.string.p3x_power_off)
            TooltipCompat.setTooltipText(this, context.getString(R.string.p3x_power_off))
            isClickable = true
            setBackgroundResource(R.drawable.bg_flat_ripple)
            setOnClickListener { onPower?.invoke() }
        }
        addView(power, LayoutParams(buttonSize, buttonSize))
    }

    fun select(position: Int) {
        if (position == selected) return
        val old = selected
        selected = position
        if (old in circles.indices) fade(old, false)
        if (position in circles.indices) fade(position, true)
    }

    private fun fade(i: Int, on: Boolean) {
        ObjectAnimator.ofInt(circles[i], "alpha", circles[i].alpha, if (on) 255 else 0).apply {
            duration = 180
            start()
        }
        tabButtons[i].imageTintList = ColorStateList.valueOf(if (on) onAccent() else idleTint)
        if (on) tabButtons[i].animate().cancel().also {
            tabButtons[i].scaleX = 0.8f; tabButtons[i].scaleY = 0.8f
            tabButtons[i].animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    companion object {
        fun iconFor(tabId: Int): Int = when (tabId) {
            R.id.songs -> R.drawable.p3x_tab_songs
            R.id.albums -> R.drawable.ic_album
            R.id.artists -> R.drawable.p3x_tab_artists
            R.id.genres -> R.drawable.ic_genres
            R.id.dates -> R.drawable.p3x_tab_dates
            R.id.folders -> R.drawable.ic_folder
            R.id.detailed_folders -> R.drawable.p3x_tab_filesystem
            else -> R.drawable.ic_playlist_play
        }
    }
}
