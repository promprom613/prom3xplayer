package org.akanework.gramophone.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.preference.PreferenceManager
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.request.error
import coil3.size.Scale
import com.google.android.material.card.MaterialCardView
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity
import kotlin.math.abs

/** Tiny blurred copy of cover art, shared by the full player and the mini player. */
object ArtBlur {
    fun load(ctx: Context, uri: Any?, onDone: (Drawable?) -> Unit): Disposable? {
        if (uri == null) {
            onDone(null)
            return null
        }
        return ctx.imageLoader.enqueue(
            ImageRequest.Builder(ctx).apply {
                data(uri)
                size(32, 32)
                allowConversionToBitmap(true)
                scale(Scale.FILL)
                allowHardware(false)
                target(onSuccess = {
                    val src = (it.asDrawable(ctx.resources) as? BitmapDrawable)?.bitmap
                    if (src != null) {
                        val d = BitmapDrawable(ctx.resources, blur(src))
                        d.setFilterBitmap(true)
                        onDone(d)
                    } else {
                        onDone(null)
                    }
                }, onError = { onDone(null) })
            }.build()
        )
    }

    /** Crossfades [d] into [iv] (fade-in if empty). */
    fun crossfadeInto(iv: ImageView, d: Drawable?, ms: Int = 320) {
        if (d == null) {
            iv.setImageDrawable(null)
            return
        }
        val old = iv.drawable
        iv.animate().cancel()
        if (old == null) {
            iv.setImageDrawable(d)
            iv.alpha = 0f
            iv.animate().alpha(1f).setDuration(250).start()
            return
        }
        val from = if (old is TransitionDrawable) old.getDrawable(1) else old
        val td = TransitionDrawable(arrayOf(from, d))
        td.isCrossFadeEnabled = true
        iv.alpha = 1f
        iv.setImageDrawable(td)
        td.startTransition(ms)
    }

    private fun blur(src: Bitmap): Bitmap {
        val w = 32
        val h = 32
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        var px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { px = boxPass(px, w, h) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxPass(p: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val yy = (y + dy).coerceIn(0, h - 1)
                        val c = p[yy * w + xx]
                        r += (c shr 16) and 255
                        g += (c shr 8) and 255
                        b += c and 255
                        n++
                    }
                }
                out[y * w + x] = (255 shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }
}

/**
 * Stage 2 polish for the full player: blurred background with crossfade, album/year line,
 * swipe left/right on cover + title to change song (page follows the finger), gesture exclusion.
 * Every lookup is null-safe: layouts without the new views (e.g. landscape tablet layout)
 * simply get no polish instead of a crash.
 */
class PlayerPolish(private val sheet: FullBottomSheet, private val activity: MainActivity) {

    private class Ghost(
        val cover: ImageView,
        val text: View,
        val title: TextView,
        val artist: TextView,
        val details: TextView
    ) {
        var key: Any? = null
    }

    private class Views(
        val bg: ImageView,
        val dim: View,
        val details: TextView,
        val title: TextView,
        val artist: TextView,
        val coverFrame: MaterialCardView,
        val textLayout: View,
        val gPrev: Ghost,
        val gNext: Ghost
    )

    private val views: Views? = build()
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(sheet.context).scaledTouchSlop
    private val density = sheet.context.resources.displayMetrics.density
    private val prefs = PreferenceManager.getDefaultSharedPreferences(sheet.context.applicationContext)
    private val vibrator: Vibrator? = findVibrator()

    private var disposable: Disposable? = null
    private var lastArt: Any? = null

    // swipe state
    var isTracking = false
        private set
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var velocity: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private var currentC = 0f
    private var prevItem: MediaItem? = null
    private var nextItem: MediaItem? = null
    private var restart = false
    private var commitPending = false
    private var animDone = false
    private var transitionSeen = false
    private var awaitingId: String? = null
    private var lastExclusion: Rect? = null

    private val revealRunnable = Runnable { reveal() }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadBackground(mediaItem)
            if (commitPending) {
                if (mediaItem?.mediaId == awaitingId) {
                    transitionSeen = true
                    if (animDone) tryReveal()
                }
            } else {
                refreshGhosts()
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateDetails(mediaMetadata)
            loadBackground(activity.getPlayer()?.currentMediaItem)
            refreshGhosts()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshGhosts()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            refreshGhosts()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            refreshGhosts()
        }
    }

    private fun build(): Views? {
        fun <T : View> f(id: Int): T? = sheet.findViewById<T>(id)
        val bg = f<ImageView>(R.id.bg_blur) ?: return null
        val dim = f<View>(R.id.bg_dim) ?: return null
        val details = f<TextView>(R.id.full_song_details) ?: return null
        val title = f<TextView>(R.id.full_song_name) ?: return null
        val artist = f<TextView>(R.id.full_song_artist) ?: return null
        val coverFrame = f<MaterialCardView>(R.id.album_cover_frame) ?: return null
        val textLayout = f<View>(R.id.textLayout) ?: return null
        val gPrev = Ghost(
            f<ImageView>(R.id.ghost_cover_prev) ?: return null,
            f<View>(R.id.ghost_text_prev) ?: return null,
            f<TextView>(R.id.ghost_title_prev) ?: return null,
            f<TextView>(R.id.ghost_artist_prev) ?: return null,
            f<TextView>(R.id.ghost_details_prev) ?: return null
        )
        val gNext = Ghost(
            f<ImageView>(R.id.ghost_cover_next) ?: return null,
            f<View>(R.id.ghost_text_next) ?: return null,
            f<TextView>(R.id.ghost_title_next) ?: return null,
            f<TextView>(R.id.ghost_artist_next) ?: return null,
            f<TextView>(R.id.ghost_details_next) ?: return null
        )
        return Views(bg, dim, details, title, artist, coverFrame, textLayout, gPrev, gNext)
    }

    fun attach() {
        val v = views ?: return
        v.coverFrame.radius = 0f
        v.title.gravity = Gravity.CENTER
        v.artist.gravity = Gravity.CENTER
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fixMargins()
            updateExclusion()
        }
        activity.controllerViewModel.addRecreationalPlayerListener(activity.lifecycle, listener) {
            val inst = activity.getPlayer()
            loadBackground(inst?.currentMediaItem)
            updateDetails(inst?.mediaMetadata ?: MediaMetadata.EMPTY)
            refreshGhosts()
        }
    }

    // ---------------------------------------------------------------- layout helpers

    private fun fixMargins() {
        val v = views ?: return
        val l = -sheet.paddingLeft
        val t = -sheet.paddingTop
        val r = -sheet.paddingRight
        val b = -sheet.paddingBottom
        for (x in listOf<View>(v.bg, v.dim)) {
            val lp = x.layoutParams as ConstraintLayout.LayoutParams
            if (lp.leftMargin != l || lp.topMargin != t || lp.rightMargin != r || lp.bottomMargin != b) {
                lp.setMargins(l, t, r, b)
                x.layoutParams = lp
            }
        }
        // cover goes flush to the very top (under the status bar)
        val clp = v.coverFrame.layoutParams as ConstraintLayout.LayoutParams
        if (clp.topMargin != t) {
            clp.topMargin = t
            v.coverFrame.layoutParams = clp
        }
    }

    private fun updateExclusion() {
        val v = views ?: return
        if (sheet.width <= 0 || v.textLayout.height <= 0 || v.coverFrame.height <= 0) return
        val cr = Rect(0, 0, v.coverFrame.width, v.coverFrame.height)
        sheet.offsetDescendantRectToMyCoords(v.coverFrame, cr)
        val tr = Rect(0, 0, v.textLayout.width, v.textLayout.height)
        sheet.offsetDescendantRectToMyCoords(v.textLayout, tr)
        val maxH = (200 * density).toInt()
        val bottom = tr.bottom
        val top = maxOf(cr.top, bottom - maxH, 0)
        val rect = Rect(0, top, sheet.width, bottom)
        if (rect == lastExclusion) return
        lastExclusion = rect
        ViewCompat.setSystemGestureExclusionRects(sheet, listOf(rect))
    }

    // ---------------------------------------------------------------- background + details

    private fun updateDetails(mm: MediaMetadata) {
        val v = views ?: return
        val text = detailsText(mm)
        v.details.text = text
        v.details.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun detailsText(mm: MediaMetadata): String {
        val album = mm.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        val year = mm.releaseYear ?: mm.recordingYear
        return listOfNotNull(album, year?.toString()).joinToString("  ·  ")
    }

    private fun loadBackground(item: MediaItem?) {
        val v = views ?: return
        val uri = item?.mediaMetadata?.artworkUri
        if (uri == lastArt && v.bg.drawable != null) return
        lastArt = uri
        disposable?.dispose()
        if (uri == null) {
            v.bg.setImageDrawable(null)
            return
        }
        disposable = ArtBlur.load(sheet.context, uri) { d -> show(d) }
    }

    private fun show(d: Drawable?) {
        val v = views ?: return
        if (d == null) {
            v.bg.setImageDrawable(null)
            return
        }
        val old = v.bg.drawable
        v.bg.animate().cancel()
        if (old == null) {
            v.bg.setImageDrawable(d)
            v.bg.alpha = 0f
            v.bg.animate().alpha(1f).setDuration(250).start()
            return
        }
        val from = if (old is TransitionDrawable) old.getDrawable(1) else old
        val td = TransitionDrawable(arrayOf(from, d))
        td.isCrossFadeEnabled = true
        v.bg.alpha = 1f
        v.bg.setImageDrawable(td)
        td.startTransition(320)
    }

    // ---------------------------------------------------------------- ghosts (neighbour pages)

    private fun refreshGhosts() {
        val v = views ?: return
        if (commitPending || dragging) return
        val p = activity.getPlayer() ?: return
        if (p.mediaItemCount == 0) return
        val cur = p.currentMediaItem
        val ni = p.nextMediaItemIndex
        nextItem = if (ni != C.INDEX_UNSET) p.getMediaItemAt(ni) else null
        val pi = p.previousMediaItemIndex
        restart = p.currentPosition > p.maxSeekToPreviousPosition || pi == C.INDEX_UNSET
        prevItem = if (restart) cur else p.getMediaItemAt(pi)
        nextItem?.let { bindGhost(v.gNext, it) }
        prevItem?.let { bindGhost(v.gPrev, it) }
    }

    private fun bindGhost(g: Ghost, item: MediaItem) {
        val v = views ?: return
        val md = item.mediaMetadata
        g.title.text = md.title ?: ""
        g.artist.text = md.artist ?: sheet.context.getString(R.string.unknown_artist)
        val dt = detailsText(md)
        g.details.text = dt
        g.details.visibility = if (dt.isEmpty()) View.GONE else View.VISIBLE
        g.title.setTextColor(v.title.currentTextColor)
        g.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.title.textSize)
        g.title.typeface = v.title.typeface
        g.artist.setTextColor(v.artist.currentTextColor)
        g.artist.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.artist.textSize)
        g.artist.typeface = v.artist.typeface
        g.details.setTextColor(v.details.currentTextColor)
        val uri = md.artworkUri
        if (g.key != uri || g.cover.drawable == null) {
            g.key = uri
            g.cover.loadNoPlaceholder(uri) {
                scale(Scale.FILL)
                error(R.drawable.ic_default_cover)
            }
        }
    }

    // ---------------------------------------------------------------- touch handling

    private fun inRegion(x: Float, y: Float): Boolean {
        val v = views ?: return false
        for (t in listOf(v.coverFrame, v.textLayout)) {
            val r = Rect(0, 0, t.width, t.height)
            sheet.offsetDescendantRectToMyCoords(t, r)
            if (r.contains(x.toInt(), y.toInt())) return true
        }
        return false
    }

    /** Returns true when the swipe gesture owns this touch stream. */
    fun handleTouch(ev: MotionEvent): Boolean {
        if (views == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.end()
                velocity?.recycle()
                velocity = null
                dragging = false
                isTracking = !commitPending && activity.getPlayer() != null &&
                        inRegion(ev.x, ev.y)
                startX = ev.x
                startY = ev.y
                if (isTracking) {
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return dragging
                velocity?.addMovement(ev)
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!dragging) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                        refreshGhosts()
                        dragging = true
                        sheet.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dy) > slop) {
                        isTracking = false
                    }
                }
                if (dragging) applyDrag(dx)
                return dragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                if (was) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    endDrag(ev.x - startX, vx, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                }
                dragging = false
                isTracking = false
                velocity?.recycle()
                velocity = null
                return was
            }
        }
        return dragging
    }

    private fun applyDrag(dx: Float) {
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val canGo = if (dx > 0) prevItem != null else nextItem != null
        val c = (if (canGo) dx else dx * 0.25f).coerceIn(-w, w)
        translate(c)
    }

    private fun translate(c: Float) {
        val v = views ?: return
        currentC = c
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        v.coverFrame.translationX = c
        v.textLayout.translationX = c
        setGhost(v.gPrev, c > 0f && prevItem != null, c - w)
        setGhost(v.gNext, c < 0f && nextItem != null, c + w)
    }

    private fun setGhost(g: Ghost, show: Boolean, tx: Float) {
        val vis = if (show) View.VISIBLE else View.INVISIBLE
        if (g.cover.visibility != vis) {
            g.cover.visibility = vis
            g.text.visibility = vis
        }
        g.cover.translationX = tx
        g.text.translationX = tx
    }

    private fun endDrag(dx: Float, vx: Float, canceled: Boolean) {
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val dir = if (dx > 0) 1 else -1
        val target = if (dir > 0) prevItem else nextItem
        val flingMin = 800f * density
        val flingOk = abs(vx) > flingMin && (vx > 0) == (dir > 0) && abs(dx) > slop * 2
        val p = activity.getPlayer()
        val commit = !canceled && p != null && target != null &&
                (abs(dx) > w * 0.3f || flingOk)
        if (commit && p != null) {
            commitPending = true
            animDone = false
            awaitingId = target?.mediaId
            transitionSeen = target?.mediaId == p.currentMediaItem?.mediaId
            if (dir > 0) {
                val wasRestart = restart
                p.seekToPrevious()
                if (wasRestart) tick()
            } else {
                p.seekToNext()
            }
        }
        animateTo(if (commit) dir * w else 0f, commit)
    }

    private fun animateTo(target: Float, commit: Boolean) {
        val a = ValueAnimator.ofFloat(currentC, target)
        a.duration = if (commit) 220 else 180
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { translate(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                settle = null
                if (commit) {
                    animDone = true
                    tryReveal()
                } else {
                    translate(0f)
                }
            }
        })
        settle = a
        a.start()
    }

    private fun tryReveal() {
        if (!commitPending || !animDone) return
        handler.removeCallbacks(revealRunnable)
        handler.postDelayed(revealRunnable, if (transitionSeen) 140L else 900L)
    }

    private fun reveal() {
        handler.removeCallbacks(revealRunnable)
        commitPending = false
        animDone = false
        transitionSeen = false
        awaitingId = null
        translate(0f)
        refreshGhosts()
    }

    // ---------------------------------------------------------------- haptics (UI side, restart only)

    @Suppress("DEPRECATION")
    private fun findVibrator(): Vibrator? {
        val ctx = activity
        return if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @Suppress("DEPRECATION")
    private fun tick() {
        if (!prefs.getBoolean("haptic_tick", true)) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(12L, 80))
        } else {
            vib.vibrate(12L)
        }
    }
}
