package ru.dvedev.me.yaphotoframe.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Ответ с кодом, по которому можно понять, что ссылка протухла, а не сеть пропала. */
class HttpFailure(val code: Int, url: String) : IOException("$url вернул $code") {
    /** Хранилище больше не признаёт ссылку: подписанные адреса живут часы. */
    val isStaleLink: Boolean get() = code == 410 || code == 403 || code == 404
}

/** Кладёт содержимое ссылки в кэш, если его там ещё нет. */
class MediaFetcher(
    private val http: OkHttpClient,
    private val cache: MediaCache,
) {

    /**
     * @param onProgress сколько байт уже записано; зовётся по ходу длинной
     *   загрузки, чтобы страница могла показать, как качается гигабайтный ролик.
     */
    suspend fun ensure(
        key: String,
        url: String,
        onProgress: (Long) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (cache.has(key)) {
            cache.touch(key)
            return@withContext cache.file(key)
        }

        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, url)
            val body = response.body ?: throw IOException("пустой ответ от $url")
            cache.put(key) { target ->
                target.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    var reported = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - reported >= PROGRESS_STEP_BYTES) {
                            reported = written
                            onProgress(written)
                        }
                    }
                    onProgress(written)
                }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024

        /** Чаще, чем раз в мегабайт, сообщать незачем. */
        const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
    }
}
