package ru.dvedev.me.yaphotoframe.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCorruptionTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `живой кадр не считается мусором`() {
        val pixels = IntArray(1000) { i -> argb((i * 7) % 200 + 20, (i * 13) % 180 + 30, (i * 3) % 160 + 40) }
        assertFalse(FrameCorruption.looksCorrupted(pixels))
    }

    @Test
    fun `зелёная трава — не мусор`() {
        val pixels = IntArray(1000) { argb(70, 160, 60) }
        assertFalse(FrameCorruption.looksCorrupted(pixels))
    }

    @Test
    fun `полосы неинициализированного YUV ловятся`() {
        val pixels = IntArray(1000) { i -> if (i % 5 == 0) argb(0, 255, 0) else if (i % 7 == 0) argb(255, 0, 255) else argb(120, 100, 90) }
        assertTrue(FrameCorruption.looksCorrupted(pixels))
    }

    @Test
    fun `пустой кадр — не мусор`() {
        assertFalse(FrameCorruption.looksCorrupted(IntArray(0)))
    }
}
