package org.akanework.gramophone.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.allowHardware
import coil3.size.Scale
import com.google.android.material.card.MaterialCardView
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.MainActivity

class PlayerPolish(private val sheet: FullBottomSheet, private val activity: MainActivity) {

    private val bg: ImageView = sheet.findViewById(R.id.bg_blur)
    private val dim: View = sheet.findViewById(R.id.bg_dim)
    private val details: TextView = sheet.findViewById(R.id.full_song_details)
    private var disposable: Disposable? = null
    private var lastArt: Any? = null
    private val vibrator: Vibrator? = findVibrator()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadBackground(mediaItem)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) tick()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateDetails(mediaMetadata)
            loadBackground(activity.getPlayer()?.currentMediaItem)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) tick()
        }
    }

    fun attach() {
        sheet.findViewById<MaterialCardView>(R.id.album_cover_frame).radius = 0f
        sheet.findViewById<TextView>(R.id.full_song_name).gravity = Gravity.CENTER
        sheet.findViewById<TextView>(R.id.full_song_artist).gravity = Gravity.CENTER
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fixMargins() }
        activity.controllerViewModel.addRecreationalPlayerListener(activity.lifecycle, listener) {
            val inst = activity.getPlayer()
            loadBackground(inst?.currentMediaItem)
            updateDetails(inst?.mediaMetadata ?: MediaMetadata.EMPTY)
        }
    }

    private fun fixMargins() {
        val l = -sheet.paddingLeft
        val t = -sheet.paddingTop
        val r = -sheet.paddingRight
        val b = -sheet.paddingBottom
        for (v in listOf<View>(bg, dim)) {
            val lp = v.layoutParams as ConstraintLayout.LayoutParams
            if (lp.leftMargin != l || lp.topMargin != t || lp.rightMargin != r || lp.bottomMargin != b) {
                lp.setMargins(l, t, r, b)
                v.layoutParams = lp
            }
        }
    }

    private fun updateDetails(mm: MediaMetadata) {
        val album = mm.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        val year = mm.releaseYear ?: mm.recordingYear
        val text = listOfNotNull(album, year?.toString()).joinToString("  ·  ")
        details.text = text
        details.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadBackground(item: MediaItem?) {
        val uri = item?.mediaMetadata?.artworkUri
        if (uri == lastArt && bg.drawable != null) return
        lastArt = uri
        disposable?.dispose()
        if (uri == null) {
            bg.setImageDrawable(null)
            return
        }
        val ctx = sheet.context
        disposable = ctx.imageLoader.enqueue(
            ImageRequest.Builder(ctx).apply {
                data(uri)
                size(32, 32)
                allowConversionToBitmap(true)
                scale(Scale.FILL)
                allowHardware(false)
                target(onSuccess = {
                    val src = (it.asDrawable(ctx.resources) as? BitmapDrawable)?.bitmap
                    if (src != null) show(blur(src)) else bg.setImageDrawable(null)
                }, onError = { bg.setImageDrawable(null) })
            }.build()
        )
    }

    private fun show(bmp: Bitmap) {
        val d = BitmapDrawable(sheet.context.resources, bmp)
        d.setFilterBitmap(true)
        bg.setImageDrawable(d)
        bg.alpha = 0f
        bg.animate().alpha(1f).setDuration(250).start()
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

    @Suppress("DEPRECATION")
    private fun findVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    @Suppress("DEPRECATION")
    private fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(12L, 80))
        } else {
            v.vibrate(12L)
        }
    }
}
