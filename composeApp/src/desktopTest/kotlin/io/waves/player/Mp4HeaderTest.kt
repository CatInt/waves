package io.waves.player

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mp4HeaderTest {
    @Test fun fragmentedFileLosesItsMoovDurations() {
        val file = mp4(fragmented = true)
        assertTrue(Mp4Header.clearFragmentedDurations(file))
        assertEquals(0, mvhdDuration(file))
        assertEquals(0, tkhdDuration(file))
        assertEquals(0, mdhdDuration(file))
    }

    @Test fun regularFileIsLeftAlone() {
        val file = mp4(fragmented = false)
        val original = file.copyOf()
        assertTrue(Mp4Header.clearFragmentedDurations(file))
        assertContentEquals(original, file)
    }

    @Test fun waitsForTheWholeMoov() {
        val file = mp4(fragmented = true)
        val partial = file.copyOf(MOOV_AT + 20)
        assertFalse(Mp4Header.clearFragmentedDurations(partial))
    }

    @Test fun nonMp4IsLeftAlone() {
        val mp3 = "ID3".toByteArray() + ByteArray(100) { 7 }
        assertTrue(Mp4Header.clearFragmentedDurations(mp3.copyOf()))
    }

    @Test fun patchesAFileInPlace() {
        val file = File.createTempFile("waves-mp4", ".m4a").apply { deleteOnExit() }
        file.writeBytes(mp4(fragmented = true) + ByteArray(1000))
        Mp4Header.clearFragmentedDurations(file)
        val bytes = file.readBytes()
        assertEquals(0, mvhdDuration(bytes))
        assertEquals(1000 + mp4(fragmented = true).size, bytes.size)
    }

    companion object {
        private const val DURATION = 157_547_520
        private const val MOOV_AT = 24

        private fun box(type: String, vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
            return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        }

        private fun ints(vararg values: Int) = ByteBuffer.allocate(4 * values.size).apply { values.forEach { putInt(it) } }.array()

        /** ftyp + moov(mvhd, [mvex], trak(tkhd, mdia(mdhd))) + sidx, laid out like YouTube's DASH audio. */
        fun mp4(fragmented: Boolean): ByteArray {
            val mvhd = box("mvhd", ints(0, 1, 1, 44_100, DURATION), ByteArray(80))
            val tkhd = box("tkhd", ints(3, 1, 1, 1, 0, DURATION), ByteArray(60))
            val mdhd = box("mdhd", ints(0, 1, 1, 44_100, DURATION, 0))
            val mvex = box("mvex", box("trex", ints(0, 1, 1, 0, 0, 0)))
            val trak = box("trak", tkhd, box("mdia", mdhd))
            val moov = if (fragmented) box("moov", mvhd, mvex, trak) else box("moov", mvhd, trak)
            return ByteArrayOutputStream().apply {
                write(box("ftyp", "dash".toByteArray(), ints(0), "iso6mp41".toByteArray()))
                write(moov)
                write(box("sidx", ints(0, 1, 44_100)))
            }.toByteArray()
        }

        private fun at(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).int
        private fun find(bytes: ByteArray, type: String) = String(bytes, Charsets.ISO_8859_1).indexOf(type) - 4
        fun mvhdDuration(bytes: ByteArray) = at(bytes, find(bytes, "mvhd") + 8 + 16)
        fun tkhdDuration(bytes: ByteArray) = at(bytes, find(bytes, "tkhd") + 8 + 20)
        fun mdhdDuration(bytes: ByteArray) = at(bytes, find(bytes, "mdhd") + 8 + 16)
    }
}
