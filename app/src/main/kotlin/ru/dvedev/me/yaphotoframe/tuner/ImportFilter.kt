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

    const val VOLUME_KEY = "storageVolumeUuid"

    /** Так том назывался в файлах до 1.4 — переносится под новым именем. */
    const val OLD_VOLUME_KEY = "externalStorageUuid"

    /** Возвращает пары к применению; [hasVolume] — есть ли том с таким UUID на этом телевизоре. */
    fun filter(values: Map<String, String>, hasVolume: (String) -> Boolean): Map<String, String> {
        val chosen = (values[VOLUME_KEY] ?: values[OLD_VOLUME_KEY])?.trim()
        val rest = values - OLD_VOLUME_KEY
        if (chosen == null) return rest
        if (chosen.isEmpty() || hasVolume(chosen)) return rest + (VOLUME_KEY to chosen)
        return rest - VOLUME_KEY
    }

    /** Какой том выбран в файле, хоть под новым именем, хоть под старым. */
    fun chosenVolume(values: Map<String, String>): String? = values[VOLUME_KEY] ?: values[OLD_VOLUME_KEY]
}
