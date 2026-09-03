package ru.dvedev.me.yaphotoframe.cache

import kotlinx.coroutines.Dispatchers
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

    suspend fun ensure(key: String, url: String): File = withContext(Dispatchers.IO) {
        if (cache.has(key)) {
            cache.touch(key)
            return@withContext cache.file(key)
        }

        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, url)
            val body = response.body ?: throw IOException("пустой ответ от $url")
            cache.put(key) { target ->
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        }
    }
}
