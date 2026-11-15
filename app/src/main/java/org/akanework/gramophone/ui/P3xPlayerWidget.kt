package org.akanework.gramophone.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.LruCache
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.size.Scale
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.playOrPause

/**
 * One big player widget: song picture (dimmed 20% + dimmed sides), static two-line title/artist,
 * progress bar (tap it to jump), previous / giant play-pause / next. Song changes fade through black.
 */
class P3xPlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, awm: AppWidgetManager, appWidgetIds: IntArray) {
        idsAt = 0L
        fullUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        idsAt = 0L
    }

    override fun onDisabled(context: Context) {
        // last widget removed: free everything
        idsAt = 0L
        painted = false
        main.removeCallbacksAndMessages(null)
        pictures.evictAll()
        sizeCache = null
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, awm: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?
    ) {
        sizeCache = null // size changed: repaint the picture for the new shape
        idsAt = 0L
        fullUpdate(context)
    }

    override fun onEnabled(context: Context) {
        fullUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == A_PREV || a == A_PLAY || a == A_NEXT || a == A_SEEK) {
            val frac = intent.getFloatExtra("frac", -1f)
            withPlayer(context) { p ->
                when (a) {
                    A_PREV -> p.seekToPrevious()
                    A_NEXT -> p.seekToNext()
                    A_PLAY -> p.playOrPause()
                    A_SEEK -> {
                        val d = p.duration
                        if (frac >= 0f && d != C.TIME_UNSET && d > 0) p.seekTo((d * frac).toLong())
                    }
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    private class Snap(
        val id: String?,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val pos: Long,
        val dur: Long,
        val art: Any?,
        val nextArt: Any? = null
    )

    companion object {
        private const val A_PREV = "org.akanework.gramophone.P3XW_PREV"
        private const val A_PLAY = "org.akanework.gramophone.P3XW_PLAY"
        private const val A_NEXT = "org.akanework.gramophone.P3XW_NEXT"
        private const val A_SEEK = "org.akanework.gramophone.P3XW_SEEK"
        private const val SEGMENTS = 16
        private val main = Handler(Looper.getMainLooper())
        private val seekIds = intArrayOf(
            R.id.w_seek_0, R.id.w_seek_1, R.id.w_seek_2, R.id.w_seek_3,
            R.id.w_seek_4, R.id.w_seek_5, R.id.w_seek_6, R.id.w_seek_7,
            R.id.w_seek_8, R.id.w_seek_9, R.id.w_seek_10, R.id.w_seek_11,
            R.id.w_seek_12, R.id.w_seek_13, R.id.w_seek_14, R.id.w_seek_15
        )

        private var lastId: String? = null
        private var painted = false
        private var page = 0 // which flipper page is on screen
        private var loadSeq = 0
        private var lastPartial = 0L
        private var lastSig: String? = null
        private val pictures = LruCache<String, Bitmap>(3) // composed pictures, newest 3
        private var sizeCache: Pair<Int, Int>? = null
        private var idsCache: IntArray = IntArray(0)
        private var idsAt = 0L
        private val titleIds = intArrayOf(R.id.w_title0, R.id.w_title1)
        private val artistIds = intArrayOf(R.id.w_artist0, R.id.w_artist1)
        private val bgIds = intArrayOf(R.id.w_bg0, R.id.w_bg1)

        private val tick = object : Runnable {
            override fun run() {
                val ctx = appCtx ?: return
                val s = snap()
                if (s.playing) {
                    if (isInteractive(ctx)) partial(ctx) { setProgress(this, s) }
                    main.postDelayed(this, 1000)
                }
            }
        }
        private var appCtx: Context? = null

        private fun isInteractive(ctx: Context): Boolean = try {
            ctx.getSystemService(PowerManager::class.java)?.isInteractive != false
        } catch (_: Throwable) {
            true
        }

        // ------------------------------------------------------------------ player side

        /** Called by the playback service on every player event. */
        fun onPlayerChanged(ctx: Context) {
            try {
                appCtx = ctx.applicationContext
                if (ids(ctx).isEmpty()) return
                val s = snap()
                if (s.id != lastId || !painted) {
                    val firstPaint = !painted
                    lastId = s.id
                    if (firstPaint) fullUpdate(ctx) else transitionTo(ctx, s)
                } else {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val prog = if (s.dur > 0) (s.pos * 1000L / s.dur).toInt() else 0
                    val sig = "${s.playing}|${s.title}|${s.artist}|$prog"
                    if (now - lastPartial > 250 && sig != lastSig) {
                        lastPartial = now
                        lastSig = sig
                        partial(ctx) {
                            setProgress(this, s)
                            setTextViewText(titleIds[page], s.title)
                            setTextViewText(artistIds[page], s.artist)
                        }
                    }
                }
                main.removeCallbacks(tick)
                if (s.playing) main.postDelayed(tick, 1000)
            } catch (_: Throwable) {
            }
        }

        private fun snap(): Snap {
            val p = GramophonePlaybackService.instanceForWidgetAndLyricsOnly?.endedWorkaroundPlayer
            val item = p?.currentMediaItem
            if (p == null || item == null) return Snap(null, "", "", false, 0, 0, null)
            val md = item.mediaMetadata
            val d = p.duration.let { if (it == C.TIME_UNSET) 0L else it }
            return Snap(
                item.mediaId,
                md.title?.toString() ?: "",
                md.artist?.toString() ?: "",
                p.isPlaying,
                p.currentPosition.coerceAtLeast(0L),
                d,
                md.artworkUri,
                p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                    ?.let { p.getMediaItemAt(it).mediaMetadata.artworkUri }
            )
        }

        private fun withPlayer(ctx: Context, block: (Player) -> Unit) {
            val p = GramophonePlaybackService.instanceForWidgetAndLyricsOnly?.endedWorkaroundPlayer
            if (p != null) {
                block(p)
                return
            }
            // service is not running: connect (this starts it), run the command, disconnect
            try {
                val token = SessionToken(
                    ctx, ComponentName(ctx, GramophonePlaybackService::class.java)
                )
                val f = MediaController.Builder(ctx, token).buildAsync()
                f.addListener({
                    try {
                        val c = f.get()
                        block(c)
                        main.postDelayed({ c.release() }, 1500)
                    } catch (_: Throwable) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
            } catch (_: Throwable) {
            }
        }

        // ------------------------------------------------------------------ painting

        private fun ids(ctx: Context): IntArray {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - idsAt < 5000) return idsCache
            idsCache = try {
                AppWidgetManager.getInstance(ctx)
                    .getAppWidgetIds(ComponentName(ctx, P3xPlayerWidget::class.java))
            } catch (_: Throwable) {
                IntArray(0)
            }
            idsAt = now
            return idsCache
        }

        private fun rv(ctx: Context) = RemoteViews(ctx.packageName, R.layout.p3x_player_widget)

        private fun partial(ctx: Context, f: RemoteViews.() -> Unit) {
            try {
                val i = ids(ctx)
                if (i.isEmpty()) return
                AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(i, rv(ctx).apply(f))
            } catch (_: Throwable) {
            }
        }

        private fun setProgress(v: RemoteViews, s: Snap) {
            val prog = if (s.dur > 0) (s.pos * 1000L / s.dur).toInt().coerceIn(0, 1000) else 0
            v.setProgressBar(R.id.w_progress, 1000, prog, false)
            v.setImageViewResource(
                R.id.w_play, if (s.playing) R.drawable.p3x_w_pause else R.drawable.p3x_w_play
            )
        }

        private fun pi(ctx: Context, code: Int, action: String, frac: Float = -1f): PendingIntent {
            val i = Intent(ctx, P3xPlayerWidget::class.java).setAction(action)
            if (frac >= 0f) i.putExtra("frac", frac)
            return PendingIntent.getBroadcast(
                ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Full repaint (first time, resize, launcher restart). */
        fun fullUpdate(ctx: Context) {
            try {
                appCtx = ctx.applicationContext
                val awm = AppWidgetManager.getInstance(ctx)
                val all = ids(ctx)
                if (all.isEmpty()) return
                val s = snap()
                lastId = s.id
                loadPicture(ctx, s.art, all[0]) { bmp ->
                    val v = rv(ctx)
                    v.setOnClickPendingIntent(R.id.w_prev, pi(ctx, 1, A_PREV))
                    v.setOnClickPendingIntent(R.id.w_play, pi(ctx, 2, A_PLAY))
                    v.setOnClickPendingIntent(R.id.w_next, pi(ctx, 3, A_NEXT))
                    for (i in 0 until SEGMENTS) {
                        v.setOnClickPendingIntent(
                            seekIds[i], pi(ctx, 100 + i, A_SEEK, (i + 0.5f) / SEGMENTS)
                        )
                    }
                    v.setOnClickPendingIntent(
                        R.id.w_root, PendingIntent.getActivity(
                            ctx, 4, Intent(ctx, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    val title =
                        if (s.id == null) ctx.getString(R.string.p3x_widget_idle) else s.title
                    for (k in 0..1) {
                        v.setImageViewBitmap(bgIds[k], bmp)
                        v.setTextViewText(titleIds[k], title)
                        v.setTextViewText(artistIds[k], s.artist)
                    }
                    v.setDisplayedChild(R.id.w_flip, page)
                    setProgress(v, s)
                    awm.updateAppWidget(all, v)
                    painted = true
                    prefetch(ctx, s, all[0])
                }
                main.removeCallbacks(tick)
                if (s.playing) main.postDelayed(tick, 1000)
            } catch (_: Throwable) {
            }
        }

        /**
         * Song changed: the new picture + text go onto the hidden flipper page and the host
         * cross-fades to it natively (smooth, one single update, no timers).
         */
        private fun transitionTo(ctx: Context, s: Snap) {
            val seq = ++loadSeq
            val first = ids(ctx).firstOrNull() ?: return
            loadPicture(ctx, s.art, first) { b ->
                if (seq != loadSeq) return@loadPicture
                val t = 1 - page
                partial(ctx) {
                    setImageViewBitmap(bgIds[t], b)
                    setTextViewText(
                        titleIds[t],
                        if (s.id == null) ctx.getString(R.string.p3x_widget_idle) else s.title
                    )
                    setTextViewText(artistIds[t], s.artist)
                    setProgress(this, s)
                    setDisplayedChild(R.id.w_flip, t)
                }
                page = t
                prefetch(ctx, s, first)
            }
        }

        /** Warm the cache with the next song's picture so the next switch is instant. */
        private fun prefetch(ctx: Context, s: Snap, widgetId: Int) {
            val next = s.nextArt ?: return
            main.postDelayed({
                try {
                    loadPicture(ctx, next, widgetId) { }
                } catch (_: Throwable) {
                }
            }, 1200)
        }

        // ------------------------------------------------------------------ the picture

        private fun loadPicture(ctx: Context, art: Any?, widgetId: Int, done: (Bitmap) -> Unit) {
            val (w, h) = sizePx(ctx, widgetId)
            val key = "$art|$w|$h"
            pictures.get(key)?.let {
                done(it)
                return
            }
            fun finish(src: Bitmap?) {
                val out = compose(src, w, h)
                pictures.put(key, out)
                done(out)
            }
            if (art == null) {
                finish(null)
                return
            }
            try {
                ctx.imageLoader.enqueue(
                    ImageRequest.Builder(ctx).apply {
                        data(art)
                        size(w, h)
                        allowConversionToBitmap(true)
                        scale(Scale.FILL)
                        allowHardware(false)
                        target(onSuccess = {
                            finish((it.asDrawable(ctx.resources) as? BitmapDrawable)?.bitmap)
                        }, onError = { finish(null) })
                    }.build()
                )
            } catch (_: Throwable) {
                finish(null)
            }
        }

        /** Widget shape in pixels, scaled down so the bitmap stays small (binder limit). */
        private fun sizePx(ctx: Context, id: Int): Pair<Int, Int> {
            sizeCache?.let { return it }
            val d = ctx.resources.displayMetrics.density
            val o = try {
                AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
            } catch (_: Throwable) {
                null
            }
            val wdp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0)
                .takeIf { it > 0 } ?: 300
            val hdp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0)
                .takeIf { it > 0 } ?: 300
            var w = (wdp * d).toInt()
            var h = (hdp * d).toInt()
            val big = maxOf(w, h)
            if (big > 720) {
                w = w * 720 / big
                h = h * 720 / big
            }
            return Pair(w.coerceAtLeast(64), h.coerceAtLeast(64)).also { sizeCache = it }
        }

        /** Picture, 20% dim, dimmed sides and a soft dark base under the text/controls. */
        private fun compose(src: Bitmap?, w: Int, h: Int): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(0xFF101010.toInt())
            if (src != null && src.width > 0 && src.height > 0) {
                val sc = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
                val m = Matrix()
                m.postScale(sc, sc)
                m.postTranslate((w - src.width * sc) / 2f, (h - src.height * sc) / 2f)
                c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            c.drawColor(Color.argb(51, 0, 0, 0)) // 20% dim
            val p = Paint()
            val side = w * 0.30f
            p.shader = LinearGradient(
                0f, 0f, side, 0f, Color.argb(230, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, side, h.toFloat(), p)
            p.shader = LinearGradient(
                w.toFloat(), 0f, w - side, 0f,
                Color.argb(230, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRect(w - side, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = LinearGradient(
                0f, 0f, 0f, h * 0.32f,
                Color.argb(190, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w.toFloat(), h * 0.32f, p)
            p.shader = LinearGradient(
                0f, h * 0.35f, 0f, h.toFloat(),
                Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, h * 0.35f, w.toFloat(), h.toFloat(), p)
            return out
        }
    }
}
