package org.akanework.gramophone.logic

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/** Cheap check whether a song has lyrics: .lrc next to it, ID3 USLT/SYLT frame, or a lyrics tag. */
object P3xLyricsProbe {
    val executor = Executors.newSingleThreadExecutor()

    fun has(f: File): Boolean {
        try {
            val dir = f.parentFile
            if (dir != null && (File(dir, f.nameWithoutExtension + ".lrc").exists() ||
                        File(dir, f.nameWithoutExtension + ".LRC").exists())) return true
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(10)
                if (raf.length() < 10) return false
                raf.readFully(head)
                if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                    val v4 = head[3].toInt() == 4
                    val tagSize = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                            ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
                    var pos = 10L
                    val end = minOf(10L + tagSize, raf.length())
                    val fh = ByteArray(10)
                    while (pos + 10 <= end) {
                        raf.seek(pos)
                        raf.readFully(fh)
                        if (fh[0].toInt() == 0) break
                        val id = String(fh, 0, 4, Charsets.ISO_8859_1)
                        val size = if (v4)
                            ((fh[4].toInt() and 0x7F) shl 21) or ((fh[5].toInt() and 0x7F) shl 14) or
                                    ((fh[6].toInt() and 0x7F) shl 7) or (fh[7].toInt() and 0x7F)
                        else ((fh[4].toInt() and 0xFF) shl 24) or ((fh[5].toInt() and 0xFF) shl 16) or
                                ((fh[6].toInt() and 0xFF) shl 8) or (fh[7].toInt() and 0xFF)
                        if ((id == "USLT" || id == "SYLT") && size > 6) return true
                        if (size <= 0) break
                        pos += 10L + size
                    }
                    return false
                }
                raf.seek(0)
                val n = minOf(raf.length(), 131072L).toInt()
                val b = ByteArray(n)
                raf.readFully(b)
                val s = String(b, Charsets.ISO_8859_1)
                return s.contains("LYRICS=", true) || s.contains("UNSYNCEDLYRICS", true) ||
                        s.contains("\u00a9lyr")
            }
        } catch (_: Throwable) {
            return false
        }
    }
}
