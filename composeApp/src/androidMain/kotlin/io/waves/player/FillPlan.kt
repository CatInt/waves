package io.waves.player

/**
 * Where a background fill fetches next: the first gap at or after [playhead] (where the player reads, so what it
 * needs next comes first), else the first gap before it, so the file still ends up whole. Null once nothing is
 * missing.
 *
 * [cachedLength] answers like `Cache.getCachedLength`: from a position, how many bytes are cached there (positive),
 * or how many are missing before the next cached byte (negative). [contentLength] is the file's size, or a negative
 * number while unknown; then everything past the last cached byte counts as missing.
 */
internal fun nextFillGap(cachedLength: (position: Long, length: Long) -> Long, playhead: Long, contentLength: Long): FillGap? {
    val known = contentLength > 0
    val from = if (known) playhead.coerceIn(0, contentLength) else playhead.coerceAtLeast(0)

    fun firstGap(start: Long, end: Long): FillGap? {
        var p = start
        while (p < end) {
            val n = cachedLength(p, end - p)
            when {
                // Unknown size, nothing cached after it: missing up to an end not known yet.
                n < 0 -> return FillGap(p, if (!known && end == Long.MAX_VALUE && -n >= end - p) FillGap.UNKNOWN else minOf(-n, end - p))
                n == 0L -> return null
                else -> p += n
            }
        }
        return null
    }

    val end = if (known) contentLength else Long.MAX_VALUE
    return firstGap(from, end) ?: firstGap(0, from)
}

/** Missing bytes from [position], [length] of them; [UNKNOWN] when they run to an end not known yet. */
internal data class FillGap(val position: Long, val length: Long) {
    companion object {
        const val UNKNOWN = -1L
    }
}
