package ru.dvedev.me.yaphotoframe.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.dvedev.me.yaphotoframe.media.Folder
import java.io.File

/**
 * Список всех папок хранилища — отдельно от списка файлов.
 *
 * Нужен потому, что дерево для выбора нельзя строить по индексу файлов: там
 * лежит только выбранное, и добраться до ещё не отмеченных папок по нему
 * невозможно. А запрашивать дерево у хранилища на каждое раскрытие узла
 * означает десятки обращений подряд и заметное ожидание.
 *
 * Поэтому дерево запоминается по мере раскрытия: каждый разложенный уровень
 * ложится в этот список и второй раз открывается мгновенно, без сети. Полный
 * обход тоже есть, но он не обязателен — на большом Диске это сотни запросов
 * по секунде каждый, и ждать их незачем.
 */
class FolderIndexStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): FolderIndex {
        if (!file.exists()) return FolderIndex.EMPTY
        return try {
            val stored = json.decodeFromString(StoredFolders.serializer(), file.readText())
            FolderIndex(
                builtAtMillis = stored.builtAtMillis,
                folders = stored.folders.map { Folder(name = it.substringAfterLast('/'), path = it) },
                scanned = stored.scanned.toSet(),
            )
        } catch (e: Exception) {
            FolderIndex.EMPTY
        }
    }

    fun save(index: FolderIndex) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.parentFile?.mkdirs()
        temporary.writeText(
            json.encodeToString(
                StoredFolders.serializer(),
                StoredFolders(
                    builtAtMillis = index.builtAtMillis,
                    folders = index.folders.map { it.path },
                    scanned = index.scanned.toList(),
                ),
            )
        )
        if (!temporary.renameTo(file)) temporary.delete()
    }

    @Serializable
    private data class StoredFolders(
        @SerialName("built_at") val builtAtMillis: Long,
        val folders: List<String> = emptyList(),
        /** Пути, чьи подпапки уже известны: без этого пустую папку не отличить от неразобранной. */
        val scanned: List<String> = emptyList(),
    )
}

/** Что известно о дереве папок. */
data class FolderIndex(
    val builtAtMillis: Long,
    val folders: List<Folder>,
    /** Пути, чьи подпапки уже выяснены. */
    val scanned: Set<String>,
) {

    val isEmpty: Boolean get() = folders.isEmpty()

    /**
     * Прямые подпапки указанной, или null — если про эту папку ещё ничего не
     * известно. Пустой список и «не знаем» — разные вещи: у первой подпапок
     * нет, вторую надо спросить у хранилища.
     */
    fun childrenOf(path: String): List<Folder>? {
        if (path !in scanned) return null
        val prefix = if (path == "/") "/" else "$path/"
        return folders
            .filter { it.path.startsWith(prefix) && !it.path.removePrefix(prefix).contains('/') }
            .sortedBy { it.name.lowercase() }
    }

    /** Запоминает разложенный уровень. */
    fun withLevel(path: String, children: List<Folder>, atMillis: Long): FolderIndex {
        val known = folders.associateByTo(LinkedHashMap()) { it.path }
        children.forEach { known[it.path] = it }
        return FolderIndex(
            builtAtMillis = atMillis,
            folders = known.values.toList(),
            scanned = scanned + path,
        )
    }

    companion object {
        val EMPTY = FolderIndex(builtAtMillis = 0L, folders = emptyList(), scanned = emptySet())
    }
}
