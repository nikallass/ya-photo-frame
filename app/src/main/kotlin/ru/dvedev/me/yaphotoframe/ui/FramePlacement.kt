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
data class FramePlacement(val horizontal: Float, val vertical: Float) {

    /**
     * Куда дрейфовать кадру: по единице в каждую сторону.
     *
     * Направление берётся от размещения — кадр всегда уползает к середине
     * экрана, а не к ближнему краю. Так дрейф никогда не борется с отступом,
     * который его же и ограничивает.
     */
    fun driftDirection(): Pair<Float, Float> = Pair(
        if (horizontal < 0.5f) 1f else -1f,
        if (vertical < 0.5f) 1f else -1f,
    )

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
            )
        }
    }
}
