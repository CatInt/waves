package io.waves.player

import java.io.File
import java.io.RandomAccessFile

/**
 * Header fix-up for fragmented MP4 (YouTube's DASH audio). Its `moov` carries the full duration although the
 * samples all live in later fragments, and JavaFX adds the fragments' durations on top, reporting twice the
 * real length. Zeroing the `moov` durations (allowed for fragmented files, see ISO/IEC 14496-12) leaves only
 * the fragments to count.
 */
internal object Mp4Header {
    const val SCAN_BYTES = 256 * 1024

    /** [clearFragmentedDurations] on a file in place; a no-op for anything that isn't a fragmented MP4. */
    fun clearFragmentedDurations(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(minOf(raf.length(), SCAN_BYTES.toLong()).toInt())
            raf.readFully(head)
            val original = head.copyOf()
            clearFragmentedDurations(head)
            if (!head.contentEquals(original)) {
                raf.seek(0)
                raf.write(head)
            }
        }
    }

    /**
     * Zeroes the `mvhd`, `tkhd` and `mdhd` durations in [head] (the file's first bytes) when the file is
     * fragmented. Returns false while [head] ends before the `moov` box does; true once there is nothing more
     * to do (patched, not fragmented, or no `moov` ahead of the media data).
     */
    fun clearFragmentedDurations(head: ByteArray): Boolean {
        if (head.size >= 8 && type(head, 0) != "ftyp") return true
        var i = 0
        while (i + 8 <= head.size) {
            val size = u32(head, i)
            val type = type(head, i)
            if (type == "moof" || type == "mdat") return true
            if (size < 8) return true
            if (type == "moov") {
                if (i + size > head.size) return false
                if (find(head, i + 8, i + size.toInt(), "mvex") >= 0) clear(head, i + 8, i + size.toInt())
                return true
            }
            i += size.toInt()
        }
        return false
    }

    private fun clear(b: ByteArray, from: Int, to: Int) {
        var i = from
        while (i + 8 <= to) {
            val size = u32(b, i).toInt()
            if (size < 8 || i + size > to) return
            val body = i + 8
            val v1 = b[body].toInt() == 1
            when (type(b, i)) {
                "mvhd", "mdhd" -> zero(b, body + if (v1) 24 else 16, if (v1) 8 else 4)
                "tkhd" -> zero(b, body + if (v1) 28 else 20, if (v1) 8 else 4)
                "trak", "mdia" -> clear(b, body, i + size)
            }
            i += size
        }
    }

    private fun find(b: ByteArray, from: Int, to: Int, wanted: String): Int {
        var i = from
        while (i + 8 <= to) {
            val size = u32(b, i).toInt()
            if (type(b, i) == wanted) return i
            if (size < 8) return -1
            i += size
        }
        return -1
    }

    private fun zero(b: ByteArray, at: Int, length: Int) {
        if (at + length <= b.size) b.fill(0, at, at + length)
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    private fun type(b: ByteArray, i: Int) = String(b, i + 4, 4, Charsets.ISO_8859_1)
}
