package ru.dvedev.me.yaphotoframe.tuner

import ru.dvedev.me.yaphotoframe.media.Folder

/** Один уровень дерева папок в том виде, в каком его ждёт страница. */
/**
 * Уровень дерева для страницы. [kids] — сколько подпапок у папки, если это
 * уже известно: страница тогда рисует точку вместо треугольника у пустых,
 * не раскрывая их.
 */
fun foldersJson(
    folders: List<Folder>,
    builtAtMillis: Long,
    known: Int,
    kids: (String) -> Int? = { null },
): String {
    val items = folders.joinToString(",", "[", "]") { folder ->
        "{\"name\":\"" + jsonEscape(folder.name) + "\",\"path\":\"" + jsonEscape(folder.path) + "\"" +
            (kids(folder.path)?.let { ",\"kids\":$it" } ?: "") + "}"
    }
    return "{\"folders\":$items,\"builtAt\":$builtAtMillis,\"known\":$known}"
}

fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", " ")
