package ru.dvedev.me.yaphotoframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Правила размещения из спецификации 1.3: кадр не пересекает отступ ни в
 * начале, ни в конце показа, рост ограничен местом, путь пропорционален
 * времени, ход привязан к золотой точке, смещение — доля свободного места.
 * Проверяется на сотнях ключей: хеш раздаёт четверти и направления, и
 * правило должно держаться для любого.
 */
class FramePlanTest {

    private val W = 1920
    private val H = 1080
    private val defaults = FrameSettings()
    /** Имена как настоящие, времена показа случайные: серийные номера дают битам хеша период. */
    private val keys = java.util.Random(20260905).let { rnd ->
        (0 until 400).map { "/Лето 2023/DSC_${1000 + it}.JPG#${rnd.nextLong() and 0x7fffffffffffL}" }
    }

    private fun input(
        naturalW: Int, naturalH: Int, key: String = keys[0], settings: FrameSettings = defaults,
        portrait: Boolean = naturalH > naturalW, durationMillis: Long = 21_500L,
    ) = FramePlan.Input(W, H, naturalW, naturalH, portrait, key, settings, durationMillis)

    /** Границы ряда в конце показа: сдвиг плюс рост вокруг середины. */
    private fun endBounds(p: FramePlan.Plan): FloatArray {
        val cx = p.left + p.drawnWidth / 2f + p.travelX
        val cy = p.top + p.drawnHeight / 2f + p.travelY
        val hw = p.drawnWidth * p.toScale / 2f
        val hh = p.drawnHeight * p.toScale / 2f
        return floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    private fun assertInsideMargin(p: FramePlan.Plan, settings: FrameSettings, what: String) {
        val mx = W * settings.edgeMargin
        val my = H * settings.edgeMargin
        val tooBigX = p.drawnWidth > W - 2 * mx
        val tooBigY = p.drawnHeight > H - 2 * my
        val start = floatArrayOf(p.left.toFloat(), p.top.toFloat(), (p.left + p.drawnWidth).toFloat(), (p.top + p.drawnHeight).toFloat())
        for (b in listOf(start, endBounds(p))) {
            if (!tooBigX) {
                assertTrue("$what: левый край ${b[0]} < отступ $mx", b[0] >= mx - 0.51f)
                assertTrue("$what: правый край ${b[2]} > ${W - mx}", b[2] <= W - mx + 0.51f)
            }
            if (!tooBigY) {
                assertTrue("$what: верх ${b[1]} < отступ $my", b[1] >= my - 0.51f)
                assertTrue("$what: низ ${b[3]} > ${H - my}", b[3] <= H - my + 0.51f)
            }
            assertTrue("$what: за экран", b[0] >= -0.51f && b[1] >= -0.51f && b[2] <= W + 0.51f && b[3] <= H + 0.51f)
        }
    }

    @Test
    fun `кадр не пересекает отступ ни в начале, ни в конце показа`() {
        val sizes = listOf(1280 to 853, 853 to 1280, 1280 to 960, 960 to 1280, 1280 to 720, 1280 to 1280, 1735 to 1280)
        for (key in keys) for ((w, h) in sizes) {
            val plan = FramePlan.compute(input(w, h, key, portrait = h > w || w > 1600))
            assertInsideMargin(plan, defaults, "$key ${w}x$h")
        }
    }

    @Test
    fun `граничные настройки тоже держат отступ и экран`() {
        val extremes = listOf(
            defaults.copy(edgeMargin = 0f, zoomAmount = 0.30f, driftAmplitude = 0.30f),
            defaults.copy(edgeMargin = 0.25f),
            defaults.copy(placementStrength = 0f),
            defaults.copy(placementStrength = 1f, driftAmplitude = 0.30f, zoomAmount = 0.30f),
            defaults.copy(frameInsetLandscape = 1f, frameInsetPortrait = 1f, edgeMargin = 0f),
            defaults.copy(frameInsetLandscape = 0.3f, frameInsetPortrait = 0.3f, placementStrength = 1f),
        )
        for (s in extremes) for (key in keys.take(60)) for ((w, h) in listOf(1280 to 853, 853 to 1280)) {
            for (duration in listOf(5_000L, 21_500L, 181_500L, 3_600_000L)) {
                val plan = FramePlan.compute(input(w, h, key, s, durationMillis = duration))
                assertInsideMargin(plan, s, "$key ${w}x$h $s $duration")
            }
        }
    }

    @Test
    fun `путь пропорционален времени показа, пока хватает места`() {
        // Вертикальный снимок: по горизонтали места много, путь не режется.
        val short = FramePlan.compute(input(853, 1280, durationMillis = 10_000L))
        val long = FramePlan.compute(input(853, 1280, durationMillis = 20_000L))
        assertEquals(abs(short.travelX) * 2, abs(long.travelX), 1f)
        assertEquals(W * defaults.driftAmplitude, abs(long.travelX), 1f)
    }

    @Test
    fun `в долгом показе путь режется свободным местом, а не уводит кадр`() {
        val plan = FramePlan.compute(input(853, 1280, durationMillis = 180_000L))
        val start = plan.left + plan.drawnWidth / 2f
        val end = start + plan.travelX
        val mx = W * defaults.edgeMargin
        assertTrue(end - plan.drawnWidth * plan.toScale / 2f >= mx - 0.51f)
        assertTrue(end + plan.drawnWidth * plan.toScale / 2f <= W - mx + 0.51f)
    }

    @Test
    fun `рост пропорционален времени, но не больше потолка и не больше места`() {
        val plan10 = FramePlan.compute(input(853, 1280, durationMillis = 10_000L))
        val plan20 = FramePlan.compute(input(853, 1280, durationMillis = 20_000L))
        val plan180 = FramePlan.compute(input(853, 1280, durationMillis = 180_000L))
        assertTrue(plan10.growth < plan20.growth)
        assertTrue(plan180.growth <= FramePlan.MAX_GROWTH + 1e-6f)
        // Вертикальный снимок при 86 % высоты: сверху и снизу по 43 px за отступом,
        // рост не может быть больше 2 × 42 / 929.
        assertTrue(plan20.growth <= 2f * 42f / plan20.drawnHeight + 1e-3f)
    }

    @Test
    fun `кадр у отступа не растёт`() {
        val tight = defaults.copy(frameInsetPortrait = 1f, edgeMargin = 0.06f)
        val plan = FramePlan.compute(input(853, 1280, settings = tight))
        assertEquals(0f, plan.growth, 1e-6f)
    }

    @Test
    fun `ход заканчивается или начинается в золотой точке`() {
        var outward = 0
        var inward = 0
        for (key in keys) {
            val plan = FramePlan.compute(input(853, 1280, key, defaults.copy(driftAmplitude = 0.01f)))
            val start = plan.left + plan.drawnWidth / 2f - W / 2f
            val end = start + plan.travelX
            // Одна из точек — золотая: дальше от центра, чем другая, и с той же стороны.
            if (abs(end) > abs(start)) outward++ else inward++
            assertTrue("старт и конец по одну сторону от центра или в центре", start * end >= -1f)
        }
        assertTrue("наружу $outward", outward > keys.size / 3)
        assertTrue("внутрь $inward", inward > keys.size / 3)
    }

    @Test
    fun `смещение — доля свободного места`() {
        val strong = defaults.copy(placementStrength = 1f, driftAmplitude = 0f, zoomAmount = 0f)
        val weak = defaults.copy(placementStrength = 0f, driftAmplitude = 0f, zoomAmount = 0f)
        val centered = FramePlan.compute(input(1280, 853, settings = weak))
        assertEquals(W / 2f, centered.left + centered.drawnWidth / 2f, 1f)
        val offsets = keys.map { key ->
            val p = FramePlan.compute(input(1280, 853, key, strong))
            abs(p.left + p.drawnWidth / 2f - W / 2f)
        }
        val room = (W - 1280) / 2f - W * strong.edgeMargin - 1f
        assertTrue("не дальше свободного места", offsets.all { it <= room + 0.51f })
        assertTrue("не ближе 60 % места", offsets.all { it >= room * 0.6f - 1f })
        assertTrue("величина гуляет от кадра к кадру", offsets.toSet().size > 20)
    }

    @Test
    fun `снимки не увеличиваются, вертикальные вписываются в свою долю`() {
        val landscape = FramePlan.compute(input(1280, 853))
        assertEquals(1f, landscape.scale, 1e-6f)
        val portrait = FramePlan.compute(input(853, 1280))
        assertEquals(H * defaults.frameInsetPortrait, portrait.drawnHeight.toFloat(), 1f)
        val pair = FramePlan.compute(input(1735, 1280, portrait = true))
        assertEquals(H * defaults.frameInsetPortrait, pair.drawnHeight.toFloat(), 1f)
        val small = FramePlan.compute(input(640, 427))
        assertEquals(640, small.drawnWidth)
    }

    @Test
    fun `ключ с временем показа меняет четверть`() {
        val quadrants = (0 until 64).map { FramePlan.goldenPoint(FramePlan.showKey("/a/IMG_0001.JPG", it * 1000L)) }.toSet()
        assertEquals(4, quadrants.size)
        assertEquals(FramePlan.goldenPoint("/a/IMG_0001.JPG"), FramePlan.goldenPoint(FramePlan.showKey("/a/IMG_0001.JPG", null)))
    }
}
