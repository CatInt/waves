package io.waves.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException

/**
 * Fetches YouTube streams ([isRanged]) in bounded ranges of [chunkBytes], one request after another, and other URLs
 * as they are. YouTube throttles an open-ended request for a stream to about playback speed or slower, so playback
 * kept stalling; bounded ranges come at full speed (the download manager and the desktop relay do the same).
 */
@UnstableApi
internal class RangedHttpDataSource(
    private val http: HttpDataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
) : DataSource {
    class Factory(
        private val http: HttpDataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangedHttpDataSource(http.createDataSource(), chunkBytes)
    }

    private var spec: DataSpec? = null
    private var ranged = false
    /** Absolute position of the next byte. */
    private var position = 0L
    /** Absolute end (exclusive) of what was asked for, or [C.LENGTH_UNSET] when unknown. */
    private var end = UNSET
    /** Absolute end (exclusive) of the range being read. */
    private var chunkEnd = 0L

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        ranged = isRanged(dataSpec.uri)
        if (!ranged) return http.open(dataSpec)
        position = dataSpec.position
        end = if (dataSpec.length == UNSET) UNSET else dataSpec.position + dataSpec.length
        // Nothing there (a 416 at or past the end): an empty stream, every read at its end.
        if (!openChunk()) {
            end = position
            return 0
        }
        if (end == UNSET) total()?.let { end = it }
        return if (end == UNSET) UNSET else end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!ranged) return http.read(buffer, offset, length)
        if (length == 0) return 0
        if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
        var n = http.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            // A range that came back short means the file ended there.
            if (end == UNSET && position < chunkEnd) return C.RESULT_END_OF_INPUT
            http.close()
            if (!openChunk()) {
                end = position
                return C.RESULT_END_OF_INPUT
            }
            n = http.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) {
                // An empty range short of a known end is the server failing, not the file ending: a load error, so
                // the player retries, rather than an early end (and a short copy in the cache).
                if (end != UNSET && position < end) throw EOFException("Empty range at $position of $end")
                return n
            }
        }
        position += n
        return n
    }

    /** Opens the range starting at [position]; false when the file turned out to end there. */
    private fun openChunk(): Boolean {
        val length = if (end == UNSET) chunkBytes else minOf(chunkBytes, end - position)
        chunkEnd = position + length
        val chunk = spec!!.buildUpon().setPosition(position).setLength(length).build()
        try {
            http.open(chunk)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 416 && end == UNSET) return false
            throw e
        }
        return true
    }

    /** The whole file's size, from `Content-Range: bytes a-b/total` (or a 200's Content-Length). */
    private fun total(): Long? {
        val headers = http.responseHeaders.entries.associate { (k, v) -> k?.lowercase() to v.firstOrNull() }
        headers["content-range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { return it }
        return if (http.responseCode == 200) headers["content-length"]?.trim()?.toLongOrNull() else null
    }

    override fun getUri(): Uri? = http.uri

    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun close() {
        spec = null
        http.close()
    }

    internal companion object {
        const val DEFAULT_CHUNK_BYTES = 4L * 1024 * 1024
        private const val UNSET = C.LENGTH_UNSET.toLong()

        /** YouTube's media servers (`*.googlevideo.com/videoplayback…`), which throttle open-ended requests. */
        fun isRanged(uri: Uri): Boolean = isRanged(uri.host, uri.path)

        fun isRanged(host: String?, path: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return (h == "googlevideo.com" || h.endsWith(".googlevideo.com")) && path.orEmpty().startsWith("/videoplayback")
        }
    }
}
