package ru.dvedev.me.yaphotoframe.cache

import ru.dvedev.me.yaphotoframe.engine.ExternalStore
import ru.dvedev.me.yaphotoframe.media.MediaItem
import java.io.File

/**
 * Носитель поверх обычного кэша: ключ — путь на хранилище без ведущей косой.
 *
 * Отсюда на флешке дерево как на Диске: «Отпуск/море.mov», а не хеши. Само
 * хранение, вытеснение по давности и защита от недописанного — те же, что у
 * кэша телевизора; разница только в бюджете, который считается от свободного
 * места.
 */
class ArchiveStore(
    private val cache: MediaCache,
    private val fetcher: MediaFetcher,
) : ExternalStore {
    override fun has(path: String): Boolean = cache.has(key(path))

    override fun file(path: String): File {
        cache.touch(key(path))
        return cache.file(key(path))
    }

    override fun keys(): List<String> = cache.keys().map { "/$it" }

    override fun remove(path: String): Boolean = cache.remove(key(path))

    override suspend fun fetch(item: MediaItem, url: String, onProgress: (Long) -> Unit): File =
        fetcher.ensure(key(item.path), url, onProgress)

    override fun evict(): Int = cache.evict()

    fun usedBytes(): Long = cache.totalBytes()

    fun files(): Int = cache.count()

    fun freeBytes(): Long = cache.usableSpace()

    private fun key(path: String): String = path.trimStart('/')

}
