package ru.dvedev.me.yaphotoframe.tuner

/**
 * Что из файла настроек нельзя переносить вслепую.
 *
 * Файл, сохранённый на одном телевизоре, несёт UUID его флешки. На другом
 * телевизоре такого тома нет, и после загрузки рамка писала бы «флешка не
 * подключена», хотя своя флешка вставлена. Выбор тома из файла берётся только
 * если такой том здесь есть; иначе остаётся прежний.
 */
object ImportFilter {

    const val VOLUME_KEY = "externalStorageUuid"

    /** Возвращает пары к применению; [hasVolume] — есть ли том с таким UUID на этом телевизоре. */
    fun filter(values: Map<String, String>, hasVolume: (String) -> Boolean): Map<String, String> {
        val uuid = values[VOLUME_KEY]?.trim().orEmpty()
        if (uuid.isEmpty() || hasVolume(uuid)) return values
        return values - VOLUME_KEY
    }
}
