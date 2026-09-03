package ru.dvedev.me.yaphotoframe.video

import android.media.MediaDataSource
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache

/**
 * Ролик потоком как источник для разбора метаданных.
 *
 * Нужен ради первого кадра: постер из превью Диска — кадр откуда-то из
 * середины, и старт ролика с нуля выглядел как скачок. Читается через тот же
 * буфер, куда ролик подкачан заранее: начало файла обычно уже на диске, а чего
 * нет — доезжает по сети и заодно ложится в буфер.
 */
class CacheMediaDataSource(
    cache: SimpleCache,
    private val key: String,
    private val url: String,
    private val size: Long,
) : MediaDataSource() {

    private val source = StreamCache.dataSourceFactory(cache).createDataSource()
    private var opened = false
    private var position = -1L

    @Synchronized
    override fun readAt(pos: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (pos >= size) return -1
        // Разбор прыгает по файлу; переоткрываем только когда позиция ушла,
        // иначе каждый кусок стоил бы отдельного запроса.
        if (!opened || pos != position) {
            if (opened) source.close()
            opened = false
            source.open(
                DataSpec.Builder().setUri(url).setKey(key).setPosition(pos).setLength(C.LENGTH_UNSET.toLong()).build(),
            )
            opened = true
            position = pos
        }
        var total = 0
        while (total < count) {
            val read = source.read(buffer, offset + total, count - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }
        position += total
        return if (total == 0) -1 else total
    }

    override fun getSize(): Long = size

    @Synchronized
    override fun close() {
        if (opened) source.close()
        opened = false
    }
}
