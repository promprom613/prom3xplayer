package org.akanework.gramophone.logic

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.WeakHashMap

/** The accent color of the current song's color scheme, shared with the song lists. */
object P3xAccent {
    @Volatile
    var color: Int = 0xFFFFFFFF.toInt()
        private set

    private val drawables: MutableSet<Drawable> =
        Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())

    fun register(d: Drawable) {
        synchronized(drawables) { drawables.add(d) }
    }

    fun set(c: Int) {
        if (c == color) return
        color = c
        Handler(Looper.getMainLooper()).post {
            val copy = synchronized(drawables) { drawables.toList() }
            copy.forEach { it.invalidateSelf() }
        }
    }
}

/**
 * Background of a song row: a soft accent band while the song is playing (with equalizer
 * bars spread over the whole row) and/or a stronger tint when the row is selected.
 */
class P3xRowDecor(private val density: Float) : Drawable(), Animatable {
    var playing = false
        set(v) {
            field = v
            invalidateSelf()
        }
    var selected = false
        set(v) {
            field = v
            invalidateSelf()
        }
    var animate = false
        set(v) {
            field = v
            if (v) start() else stop()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var phase = 0f
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.09f
            invalidateSelf()
            handler.postDelayed(this, 50)
        }
    }

    init {
        P3xAccent.register(this)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val accent = P3xAccent.color
        if (selected) {
            paint.color = withAlpha(accent, 70)
            canvas.drawRect(b, paint)
        }
        if (playing) {
            paint.color = withAlpha(accent, 34)
            canvas.drawRect(b, paint)
            // equalizer bars over the whole row
            paint.color = withAlpha(accent, 56)
            val barW = 7f * density
            val gap = 5f * density
            val n = ((b.width() + gap) / (barW + gap)).toInt().coerceAtLeast(1)
            val h = b.height().toFloat()
            for (i in 0 until n) {
                val wave = Math.abs(Math.sin((phase + i * 0.62f).toDouble())).toFloat()
                val bump = 0.5f + 0.5f * Math.abs(Math.sin((i * 1.7f).toDouble())).toFloat()
                val bh = h * (0.18f + 0.62f * wave * bump)
                val l = b.left + i * (barW + gap)
                rect.set(l, b.bottom - bh, l + barW, b.bottom.toFloat())
                canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
            }
        }
    }

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    override fun start() {
        if (running || !playing) return
        running = true
        handler.post(tick)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun isRunning() = running

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

interface P3xRefreshable {
    fun p3xRefresh()
}

/** Multi-select state shared by every song list. */
object P3xSelection {
    val items = LinkedHashMap<String, androidx.media3.common.MediaItem>()
    private val adapters = ArrayList<java.lang.ref.WeakReference<P3xRefreshable>>()
    var onChanged: (() -> Unit)? = null

    fun register(a: P3xRefreshable) {
        adapters.removeAll { it.get() == null }
        if (adapters.none { it.get() === a }) adapters.add(java.lang.ref.WeakReference(a))
    }

    fun has(id: String) = items.containsKey(id)

    fun toggle(item: androidx.media3.common.MediaItem) {
        if (items.remove(item.mediaId) == null) items[item.mediaId] = item
        changed()
    }

    fun setAll(list: List<androidx.media3.common.MediaItem>) {
        list.forEach { items[it.mediaId] = it }
        changed()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        changed()
    }

    private fun changed() {
        adapters.removeAll { it.get() == null }
        adapters.forEach { it.get()?.p3xRefresh() }
        onChanged?.invoke()
    }
}

/** Draws [src] scaled to fill the bounds (center crop), like an ImageView would. */
class P3xCropDrawable(private val src: Drawable) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        val sw = src.intrinsicWidth
        val sh = src.intrinsicHeight
        if (b.isEmpty || sw <= 0 || sh <= 0) return
        val scale = Math.max(b.width().toFloat() / sw, b.height().toFloat() / sh)
        canvas.save()
        canvas.clipRect(b)
        canvas.translate(
            b.left + (b.width() - sw * scale) / 2f,
            b.top + (b.height() - sh * scale) / 2f
        )
        canvas.scale(scale, scale)
        src.setBounds(0, 0, sw, sh)
        src.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
