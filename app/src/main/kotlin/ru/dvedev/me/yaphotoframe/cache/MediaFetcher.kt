package ru.dvedev.me.yaphotoframe.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Ответ с кодом, по которому можно понять, что ссылка протухла, а не сеть пропала. */
class HttpFailure(val code: Int, url: String) : IOException("$url вернул $code") {
    /** Хранилище больше не признаёт ссылку: подписанные адреса живут часы. */
    val isStaleLink: Boolean get() = code == 410 || code == 403 || code == 404
}

/** Кладёт содержимое ссылки в хранилище или кэш, если его там ещё нет. */
class MediaFetcher(private val http: OkHttpClient) {

    /**
     * @param onProgress сколько байт уже записано; зовётся по ходу длинной
     *   загрузки, чтобы страница могла показать, как качается гигабайтное видео.
     */
    /**
     * @param yieldWhile пока возвращает true, закачка стоит: на флешке с NTFS
     *   один поток FUSE, и чтение копии снимка для показа ждёт за записью
     *   видео минутами. Кадр важнее закачки.
     */
    suspend fun ensure(
        storage: Storage,
        key: String,
        url: String,
        onProgress: (Long) -> Unit = {},
        yieldWhile: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        if (storage.has(key)) return@withContext storage.file(key)
        download(url, onProgress, yieldWhile) { write -> storage.put(key, write) }
    }

    suspend fun ensure(
        cache: MediaCache,
        key: String,
        url: String,
        onProgress: (Long) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (cache.has(key)) {
            cache.touch(key)
            return@withContext cache.file(key)
        }
        download(url, onProgress) { write -> cache.put(key, write) }
    }

    private suspend fun download(
        url: String,
        onProgress: (Long) -> Unit,
        yieldWhile: () -> Boolean = { false },
        into: (write: (File) -> Unit) -> File,
    ): File {
        val request = Request.Builder().url(url).build()
        // Отмену закачки (сменили отбор, вынули флешку) замечаем между кусками.
        val context = coroutineContext
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, url)
            val body = response.body ?: throw IOException("пустой ответ от $url")
            into { target ->
                target.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var written = 0L
                    var reported = 0L
                    while (true) {
                        context.ensureActive()
                        while (yieldWhile()) {
                            Thread.sleep(YIELD_STEP_MILLIS)
                            context.ensureActive()
                        }
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

        const val YIELD_STEP_MILLIS = 50L
    }
}
