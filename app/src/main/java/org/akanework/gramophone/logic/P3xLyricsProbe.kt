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
                if (head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                    head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()) {
                    var p = 4L
                    val bh = ByteArray(4)
                    while (p + 4 <= raf.length()) {
                        raf.seek(p); raf.readFully(bh)
                        val type = bh[0].toInt() and 0x7F
                        val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                        if (type == 4 && len in 1..4_000_000) {
                            val vb = ByteArray(len); raf.readFully(vb)
                            return String(vb, Charsets.ISO_8859_1).contains("LYRICS", true)
                        }
                        if ((bh[0].toInt() and 0x80) != 0) return false
                        p += 4L + len
                    }
                    return false
                }
                if (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) {
                    var p = 0L
                    val ah = ByteArray(8)
                    while (p + 8 <= raf.length()) {
                        raf.seek(p); raf.readFully(ah)
                        var size = (((ah[0].toInt() and 0xFF).toLong() shl 24) or ((ah[1].toInt() and 0xFF).toLong() shl 16) or
                                ((ah[2].toInt() and 0xFF).toLong() shl 8) or (ah[3].toInt() and 0xFF).toLong())
                        val t = String(ah, 4, 4, Charsets.ISO_8859_1)
                        if (size == 1L) { val eb = ByteArray(8); raf.readFully(eb); size = 0; for (x in eb) size = (size shl 8) or (x.toLong() and 0xFF) }
                        if (size < 8) return false
                        if (t == "moov") {
                            val n2 = minOf(size - 8, 8_000_000L).toInt()
                            raf.seek(p + 8)
                            val mb = ByteArray(n2); raf.readFully(mb)
                            return String(mb, Charsets.ISO_8859_1).contains("\u00a9lyr")
                        }
                        p += size
                    }
                    return false
                }
                raf.seek(0)
                val n = minOf(raf.length(), 262144L).toInt()
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
