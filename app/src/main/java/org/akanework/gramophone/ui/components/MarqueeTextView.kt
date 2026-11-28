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

package org.akanework.gramophone.ui.components

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * [MarqueeTextView] is a kind of [AppCompatTextView]
 * keeps the focus of the textView all the time so marquee
 * can be displayed properly without needing to set focus
 * manually.
 *
 * Noteworthy, when the mainThread is doing something, marquee
 * will reload and cause a fake "jitter". Use this wisely, don't
 * make it everywhere.
 */
class MarqueeTextView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(
    context,
    attrs,
    defStyleAttr,
) {
    init {
        isSingleLine = true
        ellipsize =
            android
                .text
                .TextUtils
                .TruncateAt
                .MARQUEE
        marqueeRepeatLimit = -1
        isFocusable = true
        isFocusableInTouchMode = true
        isHorizontalFadingEdgeEnabled = true
    }

    /**
     * When on, the scrolling is drawn here (not by the framework) and its position comes from a
     * clock shared by every view showing the same text. The title that slides in during a swipe
     * and the title that replaces it after the swipe therefore scroll as ONE, with no restart.
     */
    var syncMarquee: Boolean = false
        set(v) {
            field = v
            ellipsize = if (v) null else android.text.TextUtils.TruncateAt.MARQUEE
            invalidate()
        }

    /** Start (or keep) scrolling this view's current text, skipping the initial hold. */
    fun primeMarquee() {
        if (syncMarquee) P3xMarqueeClock.prime(text?.toString() ?: "")
    }

    private val syncPaint = TextPaint()

    override fun isFocused(): Boolean = true

    private var lastLayout: android.text.Layout? = null

    // a relayout (e.g. after pressing a button) silently stops the marquee: restart it once
    override fun onDraw(canvas: android.graphics.Canvas) {
        if (syncMarquee) {
            val t = text?.toString() ?: ""
            val lay = layout
            val avail = width - compoundPaddingLeft - compoundPaddingRight
            if (lay == null || t.isEmpty() || avail <= 0 ||
                paint.measureText(t) <= avail) {
                super.onDraw(canvas)
                return
            }
            val d = resources.displayMetrics.density
            syncPaint.set(paint)
            syncPaint.color = currentTextColor
            val tw = syncPaint.measureText(t)
            val cycle = tw + 56f * d
            val off = P3xMarqueeClock.offset(t, 42f * d, cycle)
            val left = compoundPaddingLeft.toFloat()
            val base = (extendedPaddingTop + lay.getLineBaseline(0)).toFloat()
            canvas.save()
            canvas.clipRect(left, 0f, (width - compoundPaddingRight).toFloat(), height.toFloat())
            canvas.drawText(t, left - off, base, syncPaint)
            canvas.drawText(t, left - off + cycle, base, syncPaint)
            canvas.restore()
            postInvalidateOnAnimation()
            return
        }
        super.onDraw(canvas)
        if (layout !== lastLayout) {
            lastLayout = layout
            post {
                super.setSelected(false)
                super.setSelected(true)
            }
        }
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        if (focused) {
            super.onFocusChanged(true, direction, previouslyFocusedRect)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus) {
            super.onWindowFocusChanged(true)
        }
    }
}

/** Shared scroll clock: same text = same scroll position, whichever view draws it. */
object P3xMarqueeClock {
    private const val HOLD_MS = 900L
    private const val STALE_MS = 1500L
    private val start = HashMap<String, Long>()
    private val seen = HashMap<String, Long>()

    /** Called for a title that is not on screen yet but will be: it should already be moving. */
    fun prime(text: String) {
        val now = SystemClock.uptimeMillis()
        val s = seen[text]
        if (s == null || now - s > STALE_MS) {
            start[text] = now - HOLD_MS
        }
        seen[text] = now + 60_000L // keep the position alive until the swipe is done
    }

    fun offset(text: String, pxPerSec: Float, cycle: Float): Float {
        val now = SystemClock.uptimeMillis()
        val s = seen[text]
        if (s == null || now - s > STALE_MS || start[text] == null) {
            start[text] = now
        }
        seen[text] = now
        if (start.size > 64) {
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > 5 * STALE_MS) {
                    start.remove(e.key)
                    it.remove()
                }
            }
        }
        val el = now - (start[text] ?: now) - HOLD_MS
        if (el <= 0) return 0f
        return ((el / 1000f) * pxPerSec) % cycle
    }
}
