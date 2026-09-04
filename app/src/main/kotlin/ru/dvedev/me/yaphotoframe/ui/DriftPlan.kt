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

    /**
     * Сколько проехать по одной оси и куда: [toward] — место в положительную
     * сторону, [back] — в отрицательную. Возвращает знак направления и длину.
     */
    private fun travel(wanted: Float, direction: Float, toward: Float, back: Float): Pair<Float, Float> {
        val ahead = (if (direction > 0) toward else back).coerceAtLeast(0f)
        val behind = (if (direction > 0) back else toward).coerceAtLeast(0f)
        return if (ahead < wanted && behind > ahead) Pair(-direction, min(wanted, behind))
        else Pair(direction, min(wanted, ahead))
    }

    /** Короче двух десятых секунды ход не бывает: иначе это рывок, а не движение. */
    const val MIN_DURATION_MILLIS = 200L

    /**
     * @param directionX +1 — вправо, −1 — влево; [directionY] так же вниз/вверх.
     * @param amplitude путь долей от ширины экрана.
     * @param zoom на сколько вырасти за показ, долей от своего размера.
     * @param edgeMargin ближе этой доли стороны экрана к краю кадр не подходит —
     *   ни в начале, ни в конце пути, вместе с ростом.
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
        edgeMargin: Float = 0f,
    ): Plan {
        val growth = zoom.coerceAtLeast(0f)
        // Приближение раздвигает кадр на половину прироста в каждую сторону —
        // столько же надо оставить до края. И ещё отступ: раньше кадр доезжал
        // до самой кромки экрана, потому что путь считался до края, а не до отступа.
        val growX = (bounds.right - bounds.left) * growth / 2
        val growY = (bounds.bottom - bounds.top) * growth / 2
        val marginX = width * edgeMargin.coerceAtLeast(0f)
        val marginY = height * edgeMargin.coerceAtLeast(0f)
        val wanted = width * amplitude.coerceAtLeast(0f)
        // Места в выбранную сторону не хватает — ехать в другую, если там его
        // больше: у прижатого к отступу кадра дорога только к середине.
        val (dirX, travelX) = travel(
            wanted,
            directionX,
            toward = width - bounds.right - growX - marginX,
            back = bounds.left - growX - marginX,
        )
        val (dirY, travelY) = travel(
            wanted,
            directionY,
            toward = height - bounds.bottom - growY - marginY,
            back = bounds.top - growY - marginY,
        )

        return Plan(
            travelX = travelX * dirX,
            travelY = travelY * dirY,
            toScale = 1f + growth,
            durationMillis = durationMillis.coerceAtLeast(MIN_DURATION_MILLIS),
        )
    }
}
