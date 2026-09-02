package ru.dvedev.me.yaphotoframe.cache

import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import java.security.MessageDigest

/**
 * Имя файла в кэше.
 *
 * Считается от пути элемента, а не от ссылки: ссылки на превью подписаны и
 * протухают, и один и тот же снимок после каждого обхода получал бы новое имя,
 * а кэш рос бы копиями одного и того же.
 */
object CacheKey {

    fun forPreview(item: MediaItem, size: PreviewSize): String = forPreview(item.path, size)

    fun forOriginal(item: MediaItem): String = forOriginal(item.path)

    // Отдельные варианты по пути нужны, когда самого элемента уже нет: файл
    // удалили с хранилища, а прибрать за ним в кэше всё равно надо.
    fun forPreview(path: String, size: PreviewSize): String =
        "${hash(path)}-${size.apiValue.lowercase()}"

    fun forOriginal(path: String): String = "${hash(path)}-orig"

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
