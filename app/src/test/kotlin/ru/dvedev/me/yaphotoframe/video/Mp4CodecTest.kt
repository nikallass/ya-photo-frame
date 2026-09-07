package ru.dvedev.me.yaphotoframe.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Кодек читается из `moov/trak/mdia/minf/stbl/stsd` вместе с длительностью:
 * по нему рамка узнаёт десятибитный HEVC до закачки.
 */
class Mp4CodecTest {

    @Test
    fun `HEVC Main 10 даёт строку с профилем 2 и битностью`() {
        val hvcC = hvcC(profileIdc = 2, tier = 0, level = 153, chroma = 1, bitDepthLuma = 10)
        val file = box("ftyp", ByteArray(8)) + moov(mvhd(600, 6_000), trak(sampleEntry("hvc1", box("hvcC", hvcC))))

        val outcome = Mp4Duration.scan(file, 0, file.size.toLong()) as Mp4Duration.Outcome.Found

        assertEquals(10_000L, outcome.millis)
        assertEquals("hvc1.2.4.L153.B0.10bit", outcome.codec)
    }

    @Test
    fun `HEVC Main 8 бит без хвоста`() {
        val hvcC = hvcC(profileIdc = 1, tier = 0, level = 120, chroma = 1, bitDepthLuma = 8)
        val file = box("ftyp", ByteArray(8)) + moov(mvhd(600, 600), trak(sampleEntry("hev1", box("hvcC", hvcC))))

        val outcome = Mp4Duration.scan(file, 0, file.size.toLong()) as Mp4Duration.Outcome.Found
        assertEquals("hev1.1.6.L120.B0", outcome.codec)
    }

    @Test
    fun `H264 даёт профиль, ограничения и уровень шестнадцатерично`() {
        val avcC = byteArrayOf(1, 0x64, 0x00, 0x28, 0xFF.toByte())
        val file = box("ftyp", ByteArray(8)) + moov(mvhd(600, 600), trak(sampleEntry("avc1", box("avcC", avcC))))

        val outcome = Mp4Duration.scan(file, 0, file.size.toLong()) as Mp4Duration.Outcome.Found
        assertEquals("avc1.640028", outcome.codec)
    }

    @Test
    fun `без stsd кодека нет, а длительность есть`() {
        val file = box("ftyp", ByteArray(8)) + moov(mvhd(600, 600), box("trak", ByteArray(4)))
        val outcome = Mp4Duration.scan(file, 0, file.size.toLong()) as Mp4Duration.Outcome.Found
        assertEquals(1_000L, outcome.millis)
        assertNull(outcome.codec)
    }

    // ── сборка атомов ──

    private fun hvcC(profileIdc: Int, tier: Int, level: Int, chroma: Int, bitDepthLuma: Int): ByteArray {
        val b = ByteArray(23)
        b[0] = 1
        b[1] = ((tier shl 5) or profileIdc).toByte()
        // Флаги совместимости: для Main 10 выставлен бит профиля 2 (старшим вперёд) → «4» в строке,
        // для Main — биты 1 и 2 → «6».
        val flags = if (profileIdc == 2) 0x20000000 else 0x60000000
        b[2] = (flags ushr 24).toByte(); b[3] = (flags ushr 16).toByte(); b[4] = (flags ushr 8).toByte(); b[5] = flags.toByte()
        b[12] = level.toByte()
        b[16] = (0xFC or chroma).toByte()
        b[17] = (0xF8 or (bitDepthLuma - 8)).toByte()
        b[18] = 0xF8.toByte()
        return b
    }

    private fun sampleEntry(type: String, vararg children: ByteArray): ByteArray {
        val fields = ByteArray(78)
        val entry = box(type, fields + children.reduce { a, c -> a + c })
        // stsd: version/flags + entry_count + запись.
        return box("stsd", be32(0) + be32(1) + entry)
    }

    private fun trak(stsd: ByteArray) = box("trak", box("mdia", box("minf", box("stbl", stsd))))

    private fun mvhd(timescale: Long, duration: Long): ByteArray =
        box("mvhd", ByteArray(4) + be32(0) + be32(0) + be32(timescale) + be32(duration) + ByteArray(80))

    private fun moov(vararg children: ByteArray) = box("moov", children.reduce { a, c -> a + c })

    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(be32((8 + payload.size).toLong())); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload)
    }.toByteArray()

    private fun be32(value: Long) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )
}
