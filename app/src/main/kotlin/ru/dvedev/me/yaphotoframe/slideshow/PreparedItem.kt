package ru.dvedev.me.yaphotoframe.slideshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
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
 * Само содержимое здесь не читается: ролик либо уже лежит на диске, либо
 * пойдёт потоком.
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
    /** Мельче скольких пикселей по длинной стороне снимок не показывать; ноль — всё. */
    private val minLongSide: () -> Int = { 0 },
    /**
     * Откуда читать ролик, идущий потоком, ради его первого кадра; null —
     * неоткуда, тогда постером будет копия с Диска.
     */
    private val streamSource: (MediaItem, Delivery.Streamed) -> MediaDataSource? = { _, _ -> null },
) {
    suspend fun prepare(item: MediaItem): PreparedItem = withContext(Dispatchers.IO) {
        val background = backgroundFor(item)
        try {
            when (item.kind) {
                MediaKind.VIDEO -> {
                    val delivery = deliver(item)
                    PreparedVideo(
                        item = item,
                        background = background,
                        poster = posterFor(item, delivery),
                        delivery = delivery,
                    )
                }
                MediaKind.PHOTO -> PreparedPhoto(
                    item = item,
                    frame = decodePhoto(previewFile(item, PreviewSize.FULL)),
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

    /**
     * Постер — ровно первый кадр ролика, когда файл под рукой.
     *
     * Копия с Диска снята где-то с первой секунды, а плеер начинает с нуля:
     * постер сменялся первым кадром с заметным «откатом» назад. Свой первый
     * кадр совпадает с тем, с чего начнёт плеер, и подмены не видно. Если
     * файла нет или кадр не достался — копия с Диска, как раньше.
     */
    private suspend fun posterFor(item: MediaItem, delivery: Delivery): Bitmap {
        val frame = when (delivery) {
            is Delivery.Local -> firstFrame { it.setDataSource(delivery.file.path) }
            is Delivery.Streamed -> streamSource(item, delivery)?.let { source ->
                source.use { firstFrame { retriever -> retriever.setDataSource(source) } }
            }
        }
        if (frame != null) return frame
        Log.d(TAG, "первый кадр ${item.name} не достался, постер — копия с Диска")
        return decode(previewFile(item, PreviewSize.FULL))
    }

    private fun firstFrame(attach: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            attach(retriever)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            // Кадр 4K целиком — тридцать мегабайт битмапа; на экран в любом
            // случае идёт не больше его ширины.
            val scale = minOf(1f, POSTER_LONG_SIDE.toFloat() / maxOf(width, height, 1))
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxOf(1, (width * scale).toInt()),
                    maxOf(1, (height * scale).toInt()),
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.also { it.prepareToDraw() }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private suspend fun backgroundFor(item: MediaItem): Bitmap {
        val micro = decode(previewFile(item, PreviewSize.MICRO))
        return try {
            BackgroundBlur.render(micro, settings().blurSampleLongSide).also { it.prepareToDraw() }
        } finally {
            micro.recycle()
        }
    }

    /**
     * Снимок, который на экране не станет почтовой маркой.
     *
     * Движок отсеивает мелочь ещё в очереди, но кадр, взятый до измерения,
     * сюда доходит — и лучше пропустить его здесь, чем показать.
     */
    private fun decodePhoto(file: File): Bitmap {
        val bitmap = decode(file)
        val minimum = minLongSide()
        if (minimum > 0 && maxOf(bitmap.width, bitmap.height) < minimum) {
            bitmap.recycle()
            throw IOException("мельче порога: ${bitmap.width}×${bitmap.height} < $minimum")
        }
        return bitmap
    }

    private fun decode(file: File): Bitmap =
        (BitmapFactory.decodeFile(file.path)
            ?: throw IOException("не удалось декодировать ${file.name}"))
            // Загрузка в GPU идёт заранее, а не на первом кадре растворения.
            .also { it.prepareToDraw() }

    private companion object {
        const val TAG = "YaPhotoFrame"

        /** Длиннее экрана постеру быть незачем. */
        const val POSTER_LONG_SIDE = 1920
    }
}
