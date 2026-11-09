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
import android.os.SystemClock
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import org.akanework.gramophone.logic.Haptics
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
 * Polish for the full player: blurred background with crossfade, album/year line,
 * swipe left/right to change song (page follows the finger, fast slide then slow brake,
 * crossfade between the songs, never locks), gesture exclusion.
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
        val cover: ImageView,
        val bg: ImageView,
        val dim: View,
        val fade: View?,
        val scrim: View?,
        val slider: View?,
        val details: TextView,
        val title: TextView,
        val artist: TextView,
        val coverFrame: MaterialCardView,
        val textLayout: View,
        val gPrev: Ghost,
        val gNext: Ghost
    )

    private val views: Views? = build()
    private val slop = ViewConfiguration.get(sheet.context).scaledTouchSlop
    private val density = sheet.context.resources.displayMetrics.density

    private var disposable: Disposable? = null
    private var lastArt: Any? = null

    // swipe state
    var isTracking = false
        private set
    private var dragging = false
    private var grabbed = false
    private var dragBase = 0f
    private var startX = 0f
    private var startY = 0f
    private var velocity: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private var currentC = 0f
    private var prevItem: MediaItem? = null
    private var nextItem: MediaItem? = null
    private var restart = false
    private var lastExclusion: Rect? = null
    private var lastDownTime = -1L
    private var lastDownEvent = -1L

    // commit (song change) state
    private var committing = false
    private var commitDir = 0
    private var commitTarget: MediaItem? = null
    private var commitOld: MediaItem? = null
    private var commitOldDrawable: Drawable? = null
    private var syncWait: String? = null

    private val hapticListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) Haptics.tick(sheet.context)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                Haptics.tick(sheet.context)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadBackground(mediaItem)
            refreshGhosts()
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
        val cover = f<ImageView>(R.id.full_sheet_cover) ?: return null
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
        return Views(
            cover, bg, dim, f<View>(R.id.bg_bottom_fade), f<View>(R.id.swipe_scrim), f<View>(R.id.slider),
            details, title, artist, coverFrame, textLayout, gPrev, gNext
        )
    }

    fun attach() {
        activity.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_START) Haptics.uiActive = true
                else if (event == Lifecycle.Event.ON_STOP) Haptics.uiActive = false
            }
        })
        activity.controllerViewModel.addRecreationalPlayerListener(
            activity.lifecycle, hapticListener
        ) { }
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
        // black fade hugs the very bottom (under the navigation bar) and both side edges
        v.fade?.let { x ->
            val lp = x.layoutParams as ConstraintLayout.LayoutParams
            if (lp.leftMargin != l || lp.topMargin != 0 || lp.rightMargin != r || lp.bottomMargin != b) {
                lp.setMargins(l, 0, r, b)
                x.layoutParams = lp
            }
        }
        // cover sits BELOW the status bar now (the padding of the sheet already leaves that room)
        val clp = v.coverFrame.layoutParams as ConstraintLayout.LayoutParams
        if (clp.topMargin != 0) {
            clp.topMargin = 0
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

    private fun refreshGhosts(force: Boolean = false) {
        val v = views ?: return
        val p = activity.getPlayer() ?: return
        val waiting = syncWait
        if (!force && waiting == null && (committing || dragging)) return
        if (committing) return
        if (waiting != null) {
            // the player has not reported the song we just swiped to yet; keep what we know
            if (p.currentMediaItem?.mediaId != waiting) return
            syncWait = null
        }
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

    /** Everything from the top of the cover down to the bottom, except the seek bar. */
    private fun inRegion(x: Float, y: Float): Boolean {
        val v = views ?: return false
        val cr = Rect(0, 0, v.coverFrame.width, v.coverFrame.height)
        sheet.offsetDescendantRectToMyCoords(v.coverFrame, cr)
        if (y < cr.top) return false
        v.slider?.let { s ->
            if (s.width > 0 && s.visibility == View.VISIBLE) {
                val sr = Rect(0, 0, s.width, s.height)
                sheet.offsetDescendantRectToMyCoords(s, sr)
                val pad = (8 * density).toInt()
                if (y >= sr.top - pad && y <= sr.bottom + pad) return false
            }
        }
        return true
    }

    /** Returns true when the swipe gesture owns this touch stream. */
    fun handleTouch(ev: MotionEvent): Boolean {
        if (views == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // this same DOWN reaches us twice (intercept + touch): handle it once
                if (ev.downTime == lastDownTime && ev.eventTime == lastDownEvent) return false
                lastDownTime = ev.downTime
                lastDownEvent = ev.eventTime
                velocity?.recycle()
                velocity = null
                dragging = false
                grabbed = false
                dragBase = 0f
                isTracking = activity.getPlayer() != null && inRegion(ev.x, ev.y)
                startX = ev.x
                startY = ev.y
                if (isTracking) {
                    // never locked: catch the page right where it is, even mid-animation
                    if (committing) {
                        grabCommit()
                    } else if (settle != null) {
                        settle?.cancel()
                        settle = null
                        dragBase = currentC
                        grabbed = true
                    }
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                } else if (!committing) {
                    settle?.end()
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
                        setLayers(true)
                        dragging = true
                        sheet.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dy) > slop) {
                        isTracking = false
                    }
                }
                if (dragging) applyDrag(dragBase + dx)
                return dragging
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                val wasGrabbed = grabbed
                val canceled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                if (was) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    endDrag(dragBase + (ev.x - startX), vx, canceled)
                } else if (wasGrabbed) {
                    // just caught it and let go: glide into place, keep the new song
                    endDrag(dragBase, 0f, true)
                }
                dragging = false
                grabbed = false
                isTracking = false
                velocity?.recycle()
                velocity = null
                return was || wasGrabbed
            }
        }
        return dragging
    }

    private fun applyDrag(total: Float) {
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val canGo = if (total > 0) prevItem != null else nextItem != null
        val c = (if (canGo) total else total * 0.25f).coerceIn(-w, w)
        translate(c)
    }

    private fun translate(c: Float) {
        val v = views ?: return
        currentC = c
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val p = (abs(c) / w).coerceIn(0f, 1f)
        val showPrev = c > 0f && prevItem != null
        val showNext = c < 0f && nextItem != null
        // outgoing fades out while the incoming fades in (only when there is an incoming page)
        val pa = if (showPrev || showNext) p else 0f
        val ov = 3f * (1f - p) // tiny overlap so no seam/bar shows between the two covers
        // covers dip through black: outgoing dies fast, incoming rises slowly, black underlay shows
        val outA = Math.pow((1f - pa).toDouble(), 1.7).toFloat()
        val inA = Math.pow(pa.toDouble(), 1.7).toFloat()
        v.scrim?.alpha = minOf(1f, pa * 6f)
        v.coverFrame.translationX = c
        v.coverFrame.alpha = outA
        v.textLayout.translationX = c
        v.textLayout.alpha = 1f - pa
        setGhost(v.gPrev, showPrev, c - w + ov, inA, pa)
        setGhost(v.gNext, showNext, c + w - ov, inA, pa)
    }

    private fun setGhost(g: Ghost, show: Boolean, tx: Float, coverAlpha: Float, alpha: Float) {
        val vis = if (show) View.VISIBLE else View.INVISIBLE
        if (g.cover.visibility != vis) {
            g.cover.visibility = vis
            g.text.visibility = vis
        }
        g.cover.translationX = tx
        g.text.translationX = tx
        g.cover.alpha = coverAlpha
        g.text.alpha = alpha
    }

    private fun setLayers(on: Boolean) {
        val v = views ?: return
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        for (x in listOf(v.coverFrame, v.textLayout, v.gPrev.cover, v.gNext.cover,
            v.gPrev.text, v.gNext.text)) {
            x.setLayerType(type, null)
        }
    }

    private fun endDrag(total: Float, vx: Float, canceled: Boolean) {
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val dir = if (total > 0) 1 else -1
        val target = if (dir > 0) prevItem else nextItem
        val flingMin = 800f * density
        val flingOk = abs(vx) > flingMin && (vx > 0) == (dir > 0) &&
                abs(total - dragBase) > slop * 2
        val p = activity.getPlayer()
        val commit = !canceled && p != null && target != null &&
                (abs(total) > w * 0.3f || flingOk)
        if (commit && p != null) {
            committing = true
            commitDir = dir
            commitTarget = target
            commitOld = p.currentMediaItem
            commitOldDrawable = views?.cover?.drawable
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

    /** Fast start, long slow brake. */
    private fun animateTo(target: Float, commit: Boolean) {
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val frac = (abs(target - currentC) / w).coerceIn(0f, 1f)
        settle?.cancel()
        val a = ValueAnimator.ofFloat(currentC, target)
        if (commit) {
            a.duration = (620f + 420f * frac).toLong()
            a.interpolator = android.view.animation.PathInterpolator(0.2f, 0.75f, 0.12f, 1f)
        } else {
            a.duration = (140f + 140f * frac).toLong()
            a.interpolator = DecelerateInterpolator(1.5f)
        }
        a.addUpdateListener { translate(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (settle === animation) settle = null
                if (canceled) return
                if (commit) {
                    finishCommit()
                } else {
                    translate(0f)
                    setLayers(false)
                    refreshGhosts()
                }
            }
        })
        settle = a
        a.start()
    }

    // ---------------------------------------------------------------- finishing a song change

    /** Real views take over the incoming song's look, so swapping back is invisible. */
    private fun adoptTarget(incoming: Ghost) {
        val v = views ?: return
        val md = commitTarget?.mediaMetadata ?: return
        forceText(v.title, md.title ?: "")
        forceText(v.artist, md.artist ?: sheet.context.getString(R.string.unknown_artist))
        updateDetails(md)
        incoming.cover.drawable?.let { d ->
            v.cover.setImageDrawable(d.constantState?.newDrawable(sheet.resources) ?: d)
        }
    }

    private fun forceText(tv: TextView, text: CharSequence) {
        (tv.getTag(R.id.fade_out_animation) as? android.view.ViewPropertyAnimator)?.cancel()
        (tv.getTag(R.id.fade_in_animation) as? android.view.ViewPropertyAnimator)?.cancel()
        tv.animate().cancel()
        tv.setTag(androidx.core.R.id.text, null)
        tv.text = text
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
    }

    private fun finishCommit() {
        val v = views ?: return
        val incoming = if (commitDir > 0) v.gPrev else v.gNext
        adoptTarget(incoming)
        committing = false
        commitTarget = null
        commitOld = null
        commitOldDrawable = null
        translate(0f)
        setLayers(false)
        refreshGhosts()
    }

    /** The user touched the page while it was still gliding in: carry on from exactly there. */
    private fun grabCommit() {
        val v = views ?: return
        val w = sheet.width.toFloat().coerceAtLeast(1f)
        val dir = commitDir
        val target = commitTarget
        val old = commitOld
        val oldDrawable = commitOldDrawable
        settle?.cancel()
        settle = null
        val incoming = if (dir > 0) v.gPrev else v.gNext
        // where the incoming page is right now becomes the new "current" offset
        val r = if (dir > 0) currentC - w else currentC + w
        adoptTarget(incoming)
        committing = false
        commitTarget = null
        commitOld = null
        commitOldDrawable = null
        // the song we just left becomes the neighbour on the opposite side
        val oldGhost = if (dir > 0) v.gNext else v.gPrev
        if (old != null) {
            bindGhost(oldGhost, old)
            if (oldDrawable != null) {
                oldGhost.cover.setImageDrawable(
                    oldDrawable.constantState?.newDrawable(sheet.resources) ?: oldDrawable
                )
                oldGhost.key = old.mediaMetadata.artworkUri
            }
            if (dir > 0) {
                nextItem = old
                prevItem = null
            } else {
                prevItem = old
                nextItem = null
            }
        }
        val p = activity.getPlayer()
        if (target != null && p != null && p.currentMediaItem?.mediaId == target.mediaId) {
            syncWait = null
            refreshGhosts(force = true)
        } else {
            syncWait = target?.mediaId
        }
        translate(r)
        dragBase = r
        grabbed = true
    }

    private fun tick() {
        Haptics.tick(sheet.context)
    }
}
