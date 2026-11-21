package org.akanework.gramophone.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** Custom cover pictures for playlists, stored privately inside the app. */
object PlaylistCovers {
    private const val SIZE = 600
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Call after a cover was saved or reset so lists can refresh their pictures. */
    fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }

    private fun dir(context: Context) = File(context.filesDir, "p3x_playlist_covers")

    private fun fileFor(context: Context, id: Long) = File(dir(context), "$id.jpg")

    /** Uri of the custom cover for the playlist with this id, or null when it has none. */
    // looked-up answers, so scrolling the playlist list does not touch the disk for every row
    private val memo = HashMap<Long, Uri?>()

    @Synchronized
    fun uriFor(context: Context, id: Long?): Uri? {
        if (id == null) return null
        if (memo.containsKey(id)) return memo[id]
        val f = fileFor(context, id)
        val uri = if (!f.exists()) null else Uri.fromFile(f).buildUpon()
            .appendQueryParameter("v", f.lastModified().toString()).build()
        memo[id] = uri
        return uri
    }

    fun hasCover(context: Context, id: Long?): Boolean = uriFor(context, id) != null

    @Synchronized
    fun reset(context: Context, id: Long) {
        fileFor(context, id).delete()
        memo.remove(id)
    }

    /** Copies [source] into a square, downscaled jpeg. Returns false if the image is unusable. */
    fun save(context: Context, id: Long, source: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE)
                sample *= 2
            val decoded = context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            } ?: return false
            val side = minOf(decoded.width, decoded.height)
            val cropped = Bitmap.createBitmap(
                decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side
            )
            val scaled = if (side > SIZE) Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true) else cropped
            dir(context).mkdirs()
            fileFor(context, id).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            synchronized(this) { memo.remove(id) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
