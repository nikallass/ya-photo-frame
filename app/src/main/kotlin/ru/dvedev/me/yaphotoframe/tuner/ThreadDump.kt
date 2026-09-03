package ru.dvedev.me.yaphotoframe.tuner

/** Стеки всех потоков процесса — текстом, для страницы и для журнала. */
object ThreadDump {

    fun text(): String = buildString {
        for ((thread, frames) in Thread.getAllStackTraces().toSortedMap(compareBy { it.name })) {
            append(thread.name).append(" [").append(thread.state).append("]\n")
            frames.take(MAX_FRAMES).forEach { append("    at ").append(it).append('\n') }
            append('\n')
        }
    }

    private const val MAX_FRAMES = 40
}
