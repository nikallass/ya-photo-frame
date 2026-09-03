package ru.dvedev.me.yaphotoframe.tuner

import ru.dvedev.me.yaphotoframe.media.Folder

/** Один уровень дерева папок в том виде, в каком его ждёт страница. */
fun foldersJson(folders: List<Folder>, builtAtMillis: Long, known: Int): String {
    val items = folders.joinToString(",", "[", "]") { folder ->
        "{\"name\":\"" + jsonEscape(folder.name) + "\",\"path\":\"" + jsonEscape(folder.path) + "\"}"
    }
    return "{\"folders\":$items,\"builtAt\":$builtAtMillis,\"known\":$known}"
}

fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", " ")
