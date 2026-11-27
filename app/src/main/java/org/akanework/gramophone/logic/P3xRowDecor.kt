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

/** The accent color of the current song's color scheme, shared with the song lists. */
object P3xAccent {
    @Volatile
    var color: Int = 0xFFFFFFFF.toInt()
        private set

    private val drawables = ArrayList<java.lang.ref.WeakReference<Drawable>>()

    fun register(d: Drawable) {
        synchronized(drawables) {
            drawables.removeAll { it.get() == null }
            drawables.add(java.lang.ref.WeakReference(d))
        }
    }

    fun set(c: Int) {
        if (c == color) return
        color = c
        Handler(Looper.getMainLooper()).post {
            val live = synchronized(drawables) {
                drawables.removeAll { it.get() == null }
                drawables.mapNotNull { it.get() }
            }
            live.forEach { it.invalidateSelf() }
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
    private var cur = FloatArray(0)
    private var tgt = FloatArray(0)
    private var spd = FloatArray(0)
    private var lastCount = 0
    private val rnd = kotlin.random.Random(System.nanoTime())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            stepBars()
            invalidateSelf()
            handler.postDelayed(this, 45)
        }
    }

    init {
        P3xAccent.register(this)
    }

    private fun barCount(w: Int): Int {
        val barW = 7f * density
        val gap = 5f * density
        return ((w + gap) / (barW + gap)).toInt().coerceAtLeast(1)
    }

    private fun ensureBars(n: Int) {
        if (n == lastCount) return
        lastCount = n
        cur = FloatArray(n) { 0.15f + rnd.nextFloat() * 0.5f }
        tgt = FloatArray(n) { rnd.nextFloat() }
        spd = FloatArray(n) { 0.15f + rnd.nextFloat() * 0.3f }
    }

    /** every bar chases its own random target at its own speed, with the odd spike */
    private fun stepBars() {
        for (i in cur.indices) {
            cur[i] += (tgt[i] - cur[i]) * spd[i]
            if (Math.abs(tgt[i] - cur[i]) < 0.05f || rnd.nextFloat() < 0.06f) {
                tgt[i] = if (rnd.nextFloat() < 0.15f) 0.85f + rnd.nextFloat() * 0.15f
                else rnd.nextFloat() * 0.8f
                spd[i] = 0.12f + rnd.nextFloat() * 0.38f
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val accent = P3xAccent.color
        if (playing) {
            // AMOLED black band with accent bars
            paint.color = 0xFF000000.toInt()
            canvas.drawRect(b, paint)
        }
        if (selected) {
            paint.color = withAlpha(accent, 70)
            canvas.drawRect(b, paint)
        }
        if (playing) {
            val barW = 7f * density
            val gap = 5f * density
            val n = barCount(b.width())
            ensureBars(n)
            paint.color = withAlpha(accent, 150)
            val h = b.height().toFloat()
            for (i in 0 until n) {
                val bh = h * (0.10f + 0.88f * cur[i].coerceIn(0f, 1f))
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

/**
 * Queue sheet background: always shows the player's CURRENT blurred cover. When the song
 * changes it fades out to black and back in with the new cover.
 */
class P3xLiveBackground(private val source: () -> Drawable?) : Drawable() {
    private var shown: Drawable? = null
    private var shownKey: Any? = null
    private var pending: Drawable? = null
    private var pendingKey: Any? = null
    private var phase = 0 // 0 idle, 1 fading to black, 2 fading back in
    private var fade = 0f
    private var last = 0L
    private val paint = Paint()

    private fun keyOf(d: Drawable?): Any? {
        if (d == null) return null
        if (d is android.graphics.drawable.BitmapDrawable) return d.bitmap
        if (d is android.graphics.drawable.TransitionDrawable && d.numberOfLayers > 0) {
            val l = d.getDrawable(d.numberOfLayers - 1)
            if (l is android.graphics.drawable.BitmapDrawable) return l.bitmap
            return l
        }
        return d
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else (now - last).coerceIn(0L, 64L)
        last = now
        val cur = source()
        val ck = keyOf(cur)
        if (shown == null) {
            shown = cur
            shownKey = ck
        } else if (ck != shownKey && cur != null) {
            if (phase == 0 || phase == 2) {
                phase = 1
            }
            pending = cur
            pendingKey = ck
        }
        when (phase) {
            1 -> {
                fade += dt / 180f
                if (fade >= 1f) {
                    fade = 1f
                    shown = pending
                    shownKey = pendingKey
                    phase = 2
                }
            }

            2 -> {
                fade -= dt / 260f
                if (fade <= 0f) {
                    fade = 0f
                    phase = 0
                }
            }
        }
        paint.color = 0xFF000000.toInt()
        canvas.drawRect(b, paint)
        val d = shown
        if (d != null) {
            val sw = d.intrinsicWidth
            val sh = d.intrinsicHeight
            if (sw > 0 && sh > 0) {
                val scale = Math.max(b.width().toFloat() / sw, b.height().toFloat() / sh)
                canvas.save()
                canvas.clipRect(b)
                canvas.translate(
                    b.left + (b.width() - sw * scale) / 2f,
                    b.top + (b.height() - sh * scale) / 2f
                )
                canvas.scale(scale, scale)
                d.setBounds(0, 0, sw, sh)
                d.draw(canvas)
                canvas.restore()
            }
            paint.color = 0x99000000.toInt()
            canvas.drawRect(b, paint)
        }
        if (fade > 0f) {
            paint.color = ((fade * 255).toInt().coerceIn(0, 255) shl 24)
            canvas.drawRect(b, paint)
        }
        // animate while fading; otherwise just check now and then for a song change
        if (phase != 0) invalidateSelf()
        else scheduleSelf(poll, android.os.SystemClock.uptimeMillis() + 300)
    }

    private val poll = Runnable { invalidateSelf() }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
