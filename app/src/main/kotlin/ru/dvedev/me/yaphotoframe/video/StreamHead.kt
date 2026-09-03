package ru.dvedev.me.yaphotoframe.video

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Начало ролика, идущего потоком, — обычным файлом.
 *
 * Нужно ради первого кадра: постер из превью Диска — кадр откуда-то из
 * середины, и старт с нуля выглядел скачком. Декодеру метаданных отдаётся
 * именно файл, а не источник с сетью внутри: с таким источником он однажды
 * повис, и рамка простояла на одном снимке, пока её не перезапустили.
 *
 * Читается через буфер потока: начало обычно уже подкачано, а чего нет —
 * доезжает по сети с обычными таймаутами и заодно ложится в буфер.
 */
object StreamHead {

    /** Кладёт первые [bytes] байт ролика в [target]; вернёт null, если не вышло. */
    suspend fun copy(cache: SimpleCache, key: String, url: String, bytes: Long, target: File): File? =
        withContext(Dispatchers.IO) {
            val source = StreamCache.dataSourceFactory(cache).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(bytes).build())
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                    }
                }
                target
            } catch (e: Exception) {
                target.delete()
                null
            } finally {
                runCatching { source.close() }
            }
        }

    /** Оглавление ролика с фотоаппарата — мегабайты, первый кадр — ещё пара; с запасом. */
    const val HEAD_BYTES = 24L * 1024 * 1024
}
