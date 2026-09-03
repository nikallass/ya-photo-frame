package ru.dvedev.me.yaphotoframe.video

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4DurationTest {

    @Test
    fun `moov в начале файла — длительность с первого куска`() {
        val file = box("ftyp", ByteArray(16)) + moov(version = 0, timescale = 600, duration = 90_000) + box("mdat", ByteArray(1000))

        val outcome = Mp4Duration.scan(file.copyOf(minOf(file.size, Mp4Duration.CHUNK_BYTES)), 0, file.size.toLong())

        assertEquals(Mp4Duration.Outcome.Found(150_000), outcome)
    }

    @Test
    fun `moov в конце — второй кусок с его смещения`() {
        val mdat = box("mdat", ByteArray(200_000))
        val head = box("ftyp", ByteArray(16))
        val file = head + mdat + moov(version = 0, timescale = 1000, duration = 12_345)
        val size = file.size.toLong()

        val first = Mp4Duration.scan(file.copyOf(Mp4Duration.CHUNK_BYTES), 0, size)
        val moovAt = (head.size + mdat.size).toLong()
        assertEquals(Mp4Duration.Outcome.MoovAt(moovAt), first)

        val second = Mp4Duration.scan(file.copyOfRange(moovAt.toInt(), file.size), moovAt, size)
        assertEquals(Mp4Duration.Outcome.Found(12_345), second)
    }

    @Test
    fun `64-битный mdat и mvhd версии 1`() {
        val payload = ByteArray(300_000)
        val largeMdat = ByteArrayOutputStream().apply {
            write(be32(1)); write("mdat".toByteArray(Charsets.ISO_8859_1)); write(be64(16L + payload.size)); write(payload)
        }.toByteArray()
        val file = box("ftyp", ByteArray(8)) + largeMdat + moov(version = 1, timescale = 90_000, duration = 4_500_000)
        val size = file.size.toLong()

        val first = Mp4Duration.scan(file.copyOf(Mp4Duration.CHUNK_BYTES), 0, size)
        val moovAt = (8 + 8 + largeMdat.size).toLong()
        assertEquals(Mp4Duration.Outcome.MoovAt(moovAt), first)
        val second = Mp4Duration.scan(file.copyOfRange(moovAt.toInt(), file.size), moovAt, size)
        assertEquals(Mp4Duration.Outcome.Found(50_000), second)
    }

    @Test
    fun `не MP4 — неизвестно, а не исключение`() {
        val junk = ByteArray(4096) { (it * 31).toByte() }
        assertTrue(Mp4Duration.scan(junk, 0, 4096) is Mp4Duration.Outcome.Unknown)
        assertTrue(Mp4Duration.scan(ByteArray(3), 0, 3) is Mp4Duration.Outcome.Unknown)
    }

    @Test
    fun `moov без mvhd — неизвестно`() {
        val file = box("ftyp", ByteArray(8)) + box("moov", box("udta", ByteArray(4)))
        assertTrue(Mp4Duration.scan(file, 0, file.size.toLong()) is Mp4Duration.Outcome.Unknown)
    }

    private fun moov(version: Int, timescale: Long, duration: Long): ByteArray {
        val mvhd = ByteArrayOutputStream().apply {
            write(version); write(ByteArray(3))
            if (version == 1) {
                write(be64(0)); write(be64(0)); write(be32(timescale)); write(be64(duration))
            } else {
                write(be32(0)); write(be32(0)); write(be32(timescale)); write(be32(duration))
            }
            write(ByteArray(80))
        }.toByteArray()
        return box("moov", box("mvhd", mvhd) + box("trak", ByteArray(40)))
    }

    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(be32((8 + payload.size).toLong())); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload)
    }.toByteArray()

    private fun be32(value: Long) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun be64(value: Long) = be32(value ushr 32) + be32(value and 0xFFFFFFFFL)
}
