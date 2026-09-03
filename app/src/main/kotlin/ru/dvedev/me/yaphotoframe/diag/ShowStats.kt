package ru.dvedev.me.yaphotoframe.diag

import java.io.File
import java.util.Calendar

/**
 * Сколько кадров показано в каждый час суток.
 *
 * Отвечает на вопрос «когда рамка вообще работает»: по гистограмме видно, что
 * телевизор простаивает по утрам и заставка живёт вечерами, — а значит и
 * пополнять папку имеет смысл к вечеру.
 *
 * Двадцать четыре числа в файле; переживает перезагрузку, потому что иначе
 * статистика обнулялась бы каждый раз и ничего не показывала.
 */
class ShowStats(private val file: File) {

    private val counts = LongArray(HOURS)

    /** Файл пишется в фоне: запись с главного потока попадала ровно на смену кадра. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "show-stats").apply { isDaemon = true }
    }

    init {
        load()
    }

    @Synchronized
    fun record(atMillis: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
        counts[calendar.get(Calendar.HOUR_OF_DAY)]++
        val line = counts.joinToString(",")
        writer.execute { save(line) }
    }

    @Synchronized
    fun byHour(): List<Long> = counts.toList()

    @Synchronized
    fun total(): Long = counts.sum()

    private fun load() {
        if (!file.isFile) return
        runCatching {
            file.readText().split(',').forEachIndexed { hour, value ->
                if (hour < HOURS) counts[hour] = value.trim().toLongOrNull() ?: 0
            }
        }
    }

    private fun save(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(line)
        }
    }

    private companion object {
        const val HOURS = 24
    }
}
