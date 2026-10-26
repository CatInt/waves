package io.waves.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream(data.size / 8 + 64).also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

internal actual fun gunzip(data: ByteArray, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream(data.size * 8)
    GZIPInputStream(data.inputStream()).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("gzip content exceeds $maxBytes bytes")
            out.write(buffer, 0, n)
        }
    }
    return out.toByteArray()
}
