package org.akanework.gramophone.logic

import android.content.Context
import java.io.File

/**
 * Persistent cache for the cover picture embedded inside a song file.
 * Reading it again means opening and parsing the audio file every time, which is slow on old
 * phones. The file name contains the song id and the file's modified time, so a retagged song
 * automatically gets a fresh entry.
 */
object P3xArtCache {
    private const val DIR = "p3x_art"
    private const val MAX_BYTES = 96L * 1024 * 1024
    private const val TRIM_TO = 64L * 1024 * 1024

    fun fileFor(context: Context, id: Long, songFile: File?): File? {
        if (songFile == null) return null
        return File(File(context.cacheDir, DIR), "${id}_${songFile.lastModified()}.img")
    }

    fun put(file: File, bytes: ByteArray) {
        try {
            val dir = file.parentFile ?: return
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            // cache is best effort
        }
    }

    /** Keeps the cache below its size limit by deleting the oldest entries first. */
    fun trim(context: Context) {
        try {
            val files = File(context.cacheDir, DIR).listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= TRIM_TO) break
                total -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * A cover borrowed from another song, for songs without one. The same song always gets the
     * same borrowed cover once picked. Null when the option is off or nothing is cached yet.
     */
    fun randomFor(context: Context, id: Long): File? {
        try {
            val ctx = context.applicationContext
            if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getBoolean("p3x_random_cover", true)) return null
            val dir = File(ctx.cacheDir, DIR)
            val map = ctx.getSharedPreferences("p3x_random_cover_map", 0)
            val saved = map.getString("c$id", null)?.let { File(dir, it) }
            if (saved != null && saved.length() > 0) return saved
            val pool = dir.listFiles { f ->
                f.name.endsWith(".img") && f.length() > 0 &&
                    !f.name.startsWith(id.toString() + "_")
            }?.sortedBy { it.name } ?: return null
            if (pool.isEmpty()) return null
            val pick = pool[Math.floorMod(id.hashCode() * 31 + 7, pool.size)]
            map.edit().putString("c$id", pick.name).apply()
            return pick
        } catch (e: Exception) {
            return null
        }
    }
}
