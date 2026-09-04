package ru.dvedev.me.yaphotoframe.ui

/**
 * Куда поставить кадр на экране: доли от 0 до 1 по горизонтали и вертикали.
 *
 * Ровно по центру кадр смотрится мёртво, поэтому он смещается к точке золотого
 * сечения. Насколько смещение окажется заметным, решает не эта величина, а
 * свободное место: [FrameView] прижимает кадр обратно, если тот подходит слишком
 * близко к краю. Для почти полноэкранного снимка сдвиг сам собой выйдет
 * небольшим, для маленького — заметным.
 */
data class FramePlacement(
    val horizontal: Float,
    val vertical: Float,
    /** Куда ехать: +1 вправо/вниз, −1 влево/вверх. */
    val driftX: Float = if (horizontal < 0.5f) 1f else -1f,
    val driftY: Float = if (vertical < 0.5f) 1f else -1f,
) {

    /**
     * Куда дрейфовать кадру: по единице в каждую сторону.
     *
     * Направление случайное, от ключа кадра: раньше кадр всегда уползал к
     * середине и за показ съедал всё смещение к золотому сечению. Если в
     * выбранную сторону места нет, [DriftPlan] развернёт ход.
     */
    fun driftDirection(): Pair<Float, Float> = Pair(driftX, driftY)

    companion object {
        private const val NEAR = 0.382f
        private const val FAR = 0.618f
        val CENTER = FramePlacement(0.5f, 0.5f)

        /**
         * Устойчивый выбор четверти по ключу кадра: пока показывается один и тот же
         * снимок, он не должен прыгать при перерисовке, но у соседних кадров
         * смещения должны быть разными.
         */
        fun goldenFor(key: String): FramePlacement {
            val hash = key.hashCode()
            return FramePlacement(
                horizontal = if (hash and 1 == 0) NEAR else FAR,
                vertical = if (hash and 2 == 0) NEAR else FAR,
                driftX = if (hash and 4 == 0) 1f else -1f,
                driftY = if (hash and 8 == 0) 1f else -1f,
            )
        }
    }
}
