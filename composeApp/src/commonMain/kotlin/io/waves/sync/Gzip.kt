package io.waves.sync

/** gzip-compresses [data] (RFC 1952). */
internal expect fun gzip(data: ByteArray): ByteArray

/**
 * Decompresses gzip [data]; throws if it is corrupt or would inflate beyond [maxBytes] (a guard against a
 * hostile or broken remote file exhausting memory).
 */
internal expect fun gunzip(data: ByteArray, maxBytes: Int = 256 * 1024 * 1024): ByteArray

/** True if [data] starts with the gzip magic number 1f 8b. */
internal fun isGzip(data: ByteArray): Boolean =
    data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
