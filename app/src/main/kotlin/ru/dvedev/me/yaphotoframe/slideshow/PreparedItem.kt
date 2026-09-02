package ru.dvedev.me.yaphotoframe.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dvedev.me.yaphotoframe.cache.Delivery
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import ru.dvedev.me.yaphotoframe.ui.BackgroundBlur
import ru.dvedev.me.yaphotoframe.ui.FrameSettings
import java.io.File
import java.io.IOException

/**
 * Готовое к показу.
 *
 * Владение битмапами переходит к слою, который это покажет. То, что так и не
 * попало на экран, обязано освободить их само — иначе на каждой отмене показа в
 * нативной памяти оставалось бы по пять мегабайт.
 */
sealed interface PreparedItem {
    val item: MediaItem
    val background: Bitmap
    fun discard()
}

/**
 * Снимок — или пара вертикальных снимков рядом.
 *
 * Вертикальный кадр занимает треть ширины экрана, и рядом с ним остаётся много
 * пустого фона. Пара заполняет экран целиком и читается как разворот альбома.
 */
class PreparedPhoto(
    override val item: MediaItem,
    val frame: Bitmap,
    override val background: Bitmap,
    val companion: Bitmap? = null,
    val companionItem: MediaItem? = null,
) : PreparedItem {

    val isPortrait: Boolean get() = frame.height > frame.width

    override fun discard() {
        frame.recycle()
        background.recycle()
        companion?.recycle()
    }
}

/**
 * Ролик: постер-кадр под фон и то, откуда его брать.
 *
 * Само содержимое не загружается заранее — тяжёлые ролики играются потоком, а
 * лёгкие уже лежат в кэше.
 */
class PreparedVideo(
    override val item: MediaItem,
    override val background: Bitmap,
    /** Кадр из ролика: закрывает собой чёрный прямоугольник, пока плеер готовится. */
    val poster: Bitmap,
    val delivery: Delivery,
) : PreparedItem {

    override fun discard() {
        background.recycle()
        poster.recycle()
    }
}

/**
 * Готовит к показу.
 *
 * Из сети здесь ничего не берётся: файлы уже лежат в кэше, потому что движок
 * подтянул их заранее.
 */
class FramePreparer(
    private val previewFile: suspend (MediaItem, PreviewSize) -> File,
    private val deliver: suspend (MediaItem) -> Delivery,
    private val settings: () -> FrameSettings,
) {
    suspend fun prepare(item: MediaItem): PreparedItem = withContext(Dispatchers.IO) {
        val background = backgroundFor(item)
        try {
            when (item.kind) {
                MediaKind.VIDEO -> PreparedVideo(
                    item = item,
                    background = background,
                    poster = decode(previewFile(item, PreviewSize.FULL)),
                    delivery = deliver(item),
                )
                MediaKind.PHOTO -> PreparedPhoto(
                    item = item,
                    frame = decode(previewFile(item, PreviewSize.FULL)),
                    background = background,
                )
            }
        } catch (e: Throwable) {
            background.recycle()
            throw e
        }
    }

    /** Сводит два вертикальных снимка в один кадр. */
    fun pair(first: PreparedPhoto, second: PreparedPhoto): PreparedPhoto {
        // Фон берём от первого, второй больше не нужен: на экране он всё равно
        // один на двоих.
        second.background.recycle()
        return PreparedPhoto(
            item = first.item,
            frame = first.frame,
            background = first.background,
            companion = second.frame,
            companionItem = second.item,
        )
    }

    private suspend fun backgroundFor(item: MediaItem): Bitmap {
        val micro = decode(previewFile(item, PreviewSize.MICRO))
        return try {
            BackgroundBlur.render(micro, settings().blurSampleLongSide)
        } finally {
            micro.recycle()
        }
    }

    private fun decode(file: File): Bitmap =
        BitmapFactory.decodeFile(file.path)
            ?: throw IOException("не удалось декодировать ${file.name}")
}
