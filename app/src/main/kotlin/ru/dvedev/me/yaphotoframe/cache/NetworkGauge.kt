package ru.dvedev.me.yaphotoframe.cache

/**
 * Скорость сети до Диска — по последним закачкам.
 *
 * Хранит три последних замера и отдаёт их среднее. Мелкие закачки (копии
 * снимков в двести килобайт) в замер не идут: на них время уходит на
 * рукопожатие, а не на передачу. Ручное значение из настроек перекрывает
 * измеренное, но само измерение продолжается — его показывает страница.
 */
class NetworkGauge(private val keep: Int = 3) {

    private val samples = ArrayDeque<Long>()

    /** Учесть закачку: сколько байт за сколько миллисекунд. */
    @Synchronized
    fun record(bytes: Long, millis: Long) {
        if (bytes < MIN_SAMPLE_BYTES || millis <= 0) return
        samples.addLast(bytes * 8_000L / millis)
        while (samples.size > keep) samples.removeFirst()
    }

    /** Среднее последних замеров, бит/с; null — замеров ещё не было. */
    @Synchronized
    fun measuredBps(): Long? = if (samples.isEmpty()) null else samples.sum() / samples.size

    @Synchronized
    fun sampleCount(): Int = samples.size

    /**
     * Что считать скоростью сети: ручное значение, если задано, иначе
     * измеренное. Пока замеров нет — null: сеть считается медленной.
     */
    fun effectiveBps(manualBps: Long): Long? = if (manualBps > 0) manualBps else measuredBps()

    companion object {
        const val MIN_SAMPLE_BYTES = 4L * 1024 * 1024
    }
}
