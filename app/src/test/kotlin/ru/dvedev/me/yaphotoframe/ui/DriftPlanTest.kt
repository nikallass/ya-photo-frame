package ru.dvedev.me.yaphotoframe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ход и рост занимают всё время показа: кадр доходит до заданного пути и роста
 * ровно к смене. Это обещание страницы настроек, и оно проверяется здесь, а не
 * глазами на телевизоре.
 */
class DriftPlanTest {

    private val screenW = 1920
    private val screenH = 1080

    /** Кадр 1280×720 в левом верхнем углу с отступом: справа и снизу места много. */
    private val topLeft = DriftPlan.Box(100f, 80f, 1380f, 800f)

    @Test
    fun `ход и рост длятся ровно время показа`() {
        val plan = DriftPlan.compute(
            screenW, screenH, topLeft, directionX = 1f, directionY = 1f,
            amplitude = 0.03f, zoom = 0.04f, durationMillis = 20_000L,
        )

        assertEquals(20_000L, plan.durationMillis)
        assertEquals(1920 * 0.03f, plan.travelX, 1e-3f)
        assertEquals(1920 * 0.03f, plan.travelY, 1e-3f)
        assertEquals(1.04f, plan.toScale, 1e-6f)
    }

    @Test
    fun `путь режется свободным местом с учётом роста`() {
        // До правого края 40 px, а рост на 10 % съедает ещё 64 px: ехать вправо некуда.
        val nearRight = DriftPlan.Box(600f, 80f, 1880f, 800f)

        val plan = DriftPlan.compute(
            screenW, screenH, nearRight, directionX = 1f, directionY = 1f,
            amplitude = 0.10f, zoom = 0.10f, durationMillis = 20_000L,
        )

        assertEquals(0f, plan.travelX, 1e-6f)
        // Вниз свободно 280 px минус 36 на рост — заданные 192 помещаются целиком.
        assertEquals(192f, plan.travelY, 1e-3f)
    }

    @Test
    fun `влево и вверх сдвиги отрицательные`() {
        val bottomRight = DriftPlan.Box(540f, 280f, 1820f, 1000f)

        val plan = DriftPlan.compute(
            screenW, screenH, bottomRight, directionX = -1f, directionY = -1f,
            amplitude = 0.03f, zoom = 0f, durationMillis = 20_000L,
        )

        assertEquals(-(1920 * 0.03f), plan.travelX, 1e-3f)
        assertEquals(-(1920 * 0.03f), plan.travelY, 1e-3f)
        assertEquals(1f, plan.toScale, 1e-6f)
    }

    @Test
    fun `нулевые путь и рост — неподвижный кадр`() {
        val plan = DriftPlan.compute(
            screenW, screenH, topLeft, directionX = 1f, directionY = 1f,
            amplitude = 0f, zoom = 0f, durationMillis = 20_000L,
        )

        assertEquals(0f, plan.travelX, 1e-6f)
        assertEquals(0f, plan.travelY, 1e-6f)
        assertEquals(1f, plan.toScale, 1e-6f)
    }

    @Test
    fun `слишком короткий показ не превращает ход в рывок`() {
        val plan = DriftPlan.compute(
            screenW, screenH, topLeft, directionX = 1f, directionY = 1f,
            amplitude = 0.03f, zoom = 0.04f, durationMillis = 10L,
        )

        assertEquals(DriftPlan.MIN_DURATION_MILLIS, plan.durationMillis)
    }

    @Test
    fun `отрицательные путь и рост не ломают расчёт`() {
        val plan = DriftPlan.compute(
            screenW, screenH, topLeft, directionX = 1f, directionY = 1f,
            amplitude = -1f, zoom = -1f, durationMillis = 20_000L,
        )

        assertEquals(0f, plan.travelX, 1e-6f)
        assertEquals(1f, plan.toScale, 1e-6f)
    }
}
