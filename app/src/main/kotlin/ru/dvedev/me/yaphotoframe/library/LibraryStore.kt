package ru.dvedev.me.yaphotoframe.library

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.PreviewUrl
import java.io.File
import java.io.IOException

/**
 * Индекс библиотеки на диске.
 *
 * Обычный файл, а не база: обход даёт тысячи элементов, они целиком помещаются в
 * память, и все запросы к ним — перебор по памяти. База добавила бы генерацию
 * кода и увела бы проверки движка в инструментальные тесты, тогда как файл
 * проверяется обычным юнит-тестом с временной директорией.
 *
 * Запись идёт через временный файл с последующей заменой: выключение
 * телевизора посреди записи не должно оставлять обрубок вместо индекса.
 */
class LibraryStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Читает потоком, а не строкой.
     *
     * Индекс целого Диска — десятки мегабайт JSON; чтение его в строку
     * требовало вчетверо больше памяти под буфер, и на телевизоре с кучей в
     * 192 МБ заставка падала при каждом запуске. Что бы ни случилось при
     * чтении — даже нехватка памяти, — рамка стартует с пустым индексом и
     * обходит папку заново, а негодный файл убирается, чтобы не падать снова.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun load(): LibrarySnapshot {
        if (!file.exists()) return LibrarySnapshot.EMPTY
        return try {
            file.inputStream().buffered().use { input ->
                json.decodeFromStream(StoredLibrary.serializer(), input).toSnapshot()
            }
        } catch (e: Throwable) {
            file.delete()
            LibrarySnapshot.EMPTY
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(snapshot: LibrarySnapshot) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.parentFile?.mkdirs()
        temporary.outputStream().buffered().use { output ->
            json.encodeToStream(StoredLibrary.serializer(), snapshot.toStored(), output)
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("не удалось заменить индекс ${file.path}")
        }
    }

    @Serializable
    private data class StoredLibrary(
        @SerialName("synced_at") val syncedAtMillis: Long,
        val entries: List<StoredEntry> = emptyList(),
    )

    @Serializable
    private data class StoredEntry(
        val path: String,
        val name: String,
        val kind: String,
        @SerialName("mime_type") val mimeType: String? = null,
        val size: Long = 0,
        @SerialName("taken_at") val takenAtMillis: Long? = null,
        @SerialName("added_at") val addedAtMillis: Long? = null,
        @SerialName("preview") val previewTemplate: String? = null,
        @SerialName("last_shown_at") val lastShownAtMillis: Long? = null,
        @SerialName("first_seen_at") val firstSeenAtMillis: Long? = null,
        @SerialName("preview_long_side") val previewLongSidePx: Int? = null,
        @SerialName("duration_ms") val durationMillis: Long? = null,
        val undecodable: Boolean = false,
    )

    private fun StoredLibrary.toSnapshot() = LibrarySnapshot(
        syncedAtMillis = syncedAtMillis,
        entries = entries.map { stored ->
            LibraryEntry(
                item = MediaItem(
                    path = stored.path,
                    name = stored.name,
                    kind = runCatching { MediaKind.valueOf(stored.kind) }.getOrDefault(MediaKind.PHOTO),
                    mimeType = stored.mimeType,
                    sizeBytes = stored.size,
                    takenAtMillis = stored.takenAtMillis,
                    addedAtMillis = stored.addedAtMillis,
                    preview = stored.previewTemplate?.let(::PreviewUrl),
                ),
                lastShownAtMillis = stored.lastShownAtMillis,
                firstSeenAtMillis = stored.firstSeenAtMillis,
                previewLongSidePx = stored.previewLongSidePx,
                durationMillis = stored.durationMillis,
                undecodable = stored.undecodable,
            )
        },
    )

    private fun LibrarySnapshot.toStored() = StoredLibrary(
        syncedAtMillis = syncedAtMillis,
        entries = entries.map { entry ->
            StoredEntry(
                path = entry.item.path,
                name = entry.item.name,
                kind = entry.item.kind.name,
                mimeType = entry.item.mimeType,
                size = entry.item.sizeBytes,
                takenAtMillis = entry.item.takenAtMillis,
                addedAtMillis = entry.item.addedAtMillis,
                previewTemplate = entry.item.preview?.template,
                lastShownAtMillis = entry.lastShownAtMillis,
                firstSeenAtMillis = entry.firstSeenAtMillis,
                previewLongSidePx = entry.previewLongSidePx,
                durationMillis = entry.durationMillis,
                undecodable = entry.undecodable,
            )
        },
    )
}

/** Содержимое индекса на определённый момент. */
data class LibrarySnapshot(
    val syncedAtMillis: Long,
    val entries: List<LibraryEntry>,
) {
    companion object {
        val EMPTY = LibrarySnapshot(syncedAtMillis = 0L, entries = emptyList())
    }
}
