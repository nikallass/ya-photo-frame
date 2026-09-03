package ru.dvedev.me.yaphotoframe.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Узнаёт длительность ролика по ссылке, не качая его.
 *
 * null — узнать не удалось: не MP4, или сеть не ответила. Что из двух, решает
 * вызывающий по исключению: ошибка сети бросается, а неразобранный
 * контейнер возвращает null молча.
 */
fun interface DurationProber {
    suspend fun probe(url: String, sizeBytes: Long): Long?

    companion object {
        val NONE = DurationProber { _, _ -> null }
    }
}

/** Замер range-запросами: по 64 КБ с начала и, если `moov` в конце, с его смещения. */
class HttpDurationProber(private val http: OkHttpClient) : DurationProber {

    override suspend fun probe(url: String, sizeBytes: Long): Long? = withContext(Dispatchers.IO) {
        var offset = 0L
        repeat(MAX_HOPS) {
            val chunk = read(url, offset, sizeBytes)
            when (val outcome = Mp4Duration.scan(chunk, offset, sizeBytes)) {
                is Mp4Duration.Outcome.Found -> return@withContext outcome.millis
                is Mp4Duration.Outcome.MoovAt -> offset = outcome.offset
                Mp4Duration.Outcome.Unknown -> return@withContext null
            }
        }
        null
    }

    private fun read(url: String, offset: Long, sizeBytes: Long): ByteArray {
        val end = minOf(offset + Mp4Duration.CHUNK_BYTES - 1, maxOf(offset, sizeBytes - 1))
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-$end").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$url вернул ${response.code}")
            val body = response.body ?: throw IOException("пустой ответ от $url")
            // Если сервер не понял Range и отдал файл целиком, берём только начало.
            val wanted = (end - offset + 1).toInt()
            val buffer = ByteArray(wanted)
            var filled = 0
            body.byteStream().use { input ->
                while (filled < wanted) {
                    val read = input.read(buffer, filled, wanted - filled)
                    if (read < 0) break
                    filled += read
                }
            }
            return if (filled == wanted) buffer else buffer.copyOf(filled)
        }
    }

    private companion object {
        const val MAX_HOPS = 3
    }
}
