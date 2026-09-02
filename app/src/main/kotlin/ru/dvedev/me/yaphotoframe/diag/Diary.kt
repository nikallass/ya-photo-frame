package ru.dvedev.me.yaphotoframe.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Короткая память о том, что происходило.
 *
 * Нужна не вместо логов, а вместо кабеля: владелец смотрит на рамку с дивана, и
 * «почему не показывается» должно отвечаться с телефона, а не через adb с
 * ноутбука. Хранится в памяти и живёт, пока живёт процесс, — для разбора
 * «что случилось только что» этого достаточно.
 */
object Diary {

    private const val TAG = "YaPhotoFrame"
    private const val CAPACITY = 200

    private val entries = ArrayDeque<Entry>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    data class Entry(val atMillis: Long, val level: String, val message: String) {
        val isError: Boolean get() = level == "ошибка"
    }

    @Synchronized
    fun note(message: String) = record("событие", message, null)

    @Synchronized
    fun problem(message: String, cause: Throwable? = null) = record("ошибка", message, cause)

    @Synchronized
    fun recent(): List<Entry> = entries.toList()

    @Synchronized
    fun errors(): List<Entry> = entries.filter { it.isError }

    fun format(entry: Entry): String =
        "${format.format(Date(entry.atMillis))} ${entry.message}"

    private fun record(level: String, message: String, cause: Throwable?) {
        val full = if (cause == null) message else "$message: ${cause.message ?: cause.javaClass.simpleName}"
        entries.addLast(Entry(System.currentTimeMillis(), level, full))
        while (entries.size > CAPACITY) entries.removeFirst()
        if (level == "ошибка") Log.w(TAG, full, cause) else Log.i(TAG, full)
    }
}
