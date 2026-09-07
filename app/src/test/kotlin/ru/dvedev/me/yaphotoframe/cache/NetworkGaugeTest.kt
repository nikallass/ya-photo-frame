package ru.dvedev.me.yaphotoframe.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkGaugeTest {

    private val mb = 1024L * 1024

    @Test
    fun `без замеров скорости нет — сеть считается медленной`() {
        val gauge = NetworkGauge()
        assertNull(gauge.measuredBps())
        assertNull(gauge.effectiveBps(manualBps = 0))
    }

    @Test
    fun `среднее последних трёх закачек, старые забываются`() {
        val gauge = NetworkGauge()
        gauge.record(100 * mb, 10_000)   // 80 Мбит/с — забудется
        gauge.record(100 * mb, 20_000)   // 40
        gauge.record(100 * mb, 40_000)   // 20
        gauge.record(100 * mb, 40_000)   // 20
        assertEquals(3, gauge.sampleCount())
        val expected = (100 * mb * 8_000L / 20_000 + 2 * (100 * mb * 8_000L / 40_000)) / 3
        assertEquals(expected, gauge.measuredBps())
    }

    @Test
    fun `мелкие закачки в замер не идут`() {
        val gauge = NetworkGauge()
        gauge.record(200 * 1024, 100)
        assertEquals(0, gauge.sampleCount())
    }

    @Test
    fun `ручное значение перекрывает измеренное`() {
        val gauge = NetworkGauge()
        gauge.record(100 * mb, 10_000)
        assertEquals(30_000_000L, gauge.effectiveBps(manualBps = 30_000_000L))
        assertEquals(gauge.measuredBps(), gauge.effectiveBps(manualBps = 0))
    }
}
