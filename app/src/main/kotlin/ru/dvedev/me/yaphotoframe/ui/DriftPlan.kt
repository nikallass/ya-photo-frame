package ru.dvedev.me.yaphotoframe.ui

import kotlin.math.min

/**
 * Расчёт хода и приближения кадра на время показа.
 *
 * Вынесен из слоя с картинками, чтобы проверяться без Android: слой умеет
 * только применить готовые цели. Ход и рост занимают всё время показа — кадр
 * доходит до заданного пути и роста ровно к смене. Отдельной скорости нет:
 * с двумя ручками кадр с умолчаниями не доезжал до цели никогда.
 */
object DriftPlan {

    /** Занятая кадром область экрана, в пикселях. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Куда и за сколько ехать.
     *
     * Сдвиги уже со знаком: направление вшито. Растёт кадр до [toScale] за то же
     * время, что и едет.
     */
    data class Plan(
        val travelX: Float,
        val travelY: Float,
        val toScale: Float,
        val durationMillis: Long,
    )

    /** Короче двух десятых секунды ход не бывает: иначе это рывок, а не движение. */
    const val MIN_DURATION_MILLIS = 200L

    /**
     * @param directionX +1 — вправо, −1 — влево; [directionY] так же вниз/вверх.
     * @param amplitude путь долей от ширины экрана.
     * @param zoom на сколько вырасти за показ, долей от своего размера.
     */
    fun compute(
        width: Int,
        height: Int,
        bounds: Box,
        directionX: Float,
        directionY: Float,
        amplitude: Float,
        zoom: Float,
        durationMillis: Long,
    ): Plan {
        val growth = zoom.coerceAtLeast(0f)
        // Приближение раздвигает кадр на половину прироста в каждую сторону —
        // столько же надо оставить до края.
        val growX = (bounds.right - bounds.left) * growth / 2
        val growY = (bounds.bottom - bounds.top) * growth / 2
        val roomX = (if (directionX > 0) width - bounds.right else bounds.left) - growX
        val roomY = (if (directionY > 0) height - bounds.bottom else bounds.top) - growY

        val wanted = width * amplitude.coerceAtLeast(0f)
        val travelX = min(wanted, roomX.coerceAtLeast(0f))
        val travelY = min(wanted, roomY.coerceAtLeast(0f))

        return Plan(
            travelX = travelX * directionX,
            travelY = travelY * directionY,
            toScale = 1f + growth,
            durationMillis = durationMillis.coerceAtLeast(MIN_DURATION_MILLIS),
        )
    }
}
