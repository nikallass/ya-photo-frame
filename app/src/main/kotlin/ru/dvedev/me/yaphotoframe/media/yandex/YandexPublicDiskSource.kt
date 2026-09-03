package ru.dvedev.me.yaphotoframe.media.yandex

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.dvedev.me.yaphotoframe.media.Folder
import ru.dvedev.me.yaphotoframe.media.FolderSelection
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.MediaSource
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import ru.dvedev.me.yaphotoframe.media.PreviewUrl
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Содержимое публично расшаренной папки Яндекс.Диска, включая подпапки.
 *
 * Авторизация не нужна: и листинг, и превью, и скачивание доступны по одной
 * ссылке. Листинг запрашивается сразу с превью максимального размера — ссылка на
 * превью содержит размер параметром, поэтому фон под кадр получается из неё же,
 * без второго обращения к API.
 *
 * Дерево обходится вширь по страницам. Плоский эндпоинт, умеющий отдать все
 * снимки одним запросом, здесь не годится: он не умеет ограничиваться папкой и
 * вернул бы содержимое всего Диска.
 *
 * @param publicKey ссылка вида `https://disk.yandex.ru/d/XXXXXXXX`.
 * @param apiBase адрес API; подменяется в тестах.
 * @param pageLimit сколько элементов просить за раз.
 */
class YandexPublicDiskSource(
    private val publicKey: String,
    private val http: OkHttpClient,
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,
    /** Что показывать: по умолчанию всю расшаренную директорию. */
    private val selection: () -> FolderSelection = { FolderSelection.ALL },
    /** Сколько файлов и папок уже пройдено — чтобы владелец видел, что обход идёт. */
    private val onProgress: (files: Int, folders: Int) -> Unit = { _, _ -> },
) : MediaSource {

    private val json = Json { ignoreUnknownKeys = true }

    /** Эндпоинт ссылки на скачивание живёт рядом с листингом. */
    private val downloadBase: HttpUrl = apiBase.newBuilder().addPathSegment("download").build()

    override suspend fun list(): List<MediaItem> = withContext(Dispatchers.IO) {
        val chosen = selection()
        val collected = mutableListOf<MediaItem>()
        val queue = ArrayDeque(listOf(ROOT_PATH to 0))
        val visited = mutableSetOf(ROOT_PATH)

        var folders = 0
        while (queue.isNotEmpty() && collected.size < MAX_ITEMS) {
            // Обход на минуты, и его отменяют, когда владелец меняет отбор:
            // проверяемся на каждой папке, между запросами.
            currentCoroutineContext().ensureActive()
            val (path, depth) = queue.removeFirst()
            val entries = listFolder(path)
            folders++
            onProgress(collected.size, folders)

            for (entry in entries) {
                when (entry.type) {
                    TYPE_DIR -> {
                        if (depth >= MAX_DEPTH) {
                            Log.w(TAG, "не спускаюсь глубже $MAX_DEPTH: ${entry.path}")
                        } else if (!chosen.shouldDescend(entry.path)) {
                            // Мимо: ни сама папка не выбрана, ни внутри неё
                            // ничего выбранного нет.
                        } else if (visited.add(entry.path)) {
                            queue.addLast(entry.path to depth + 1)
                        }
                    }

                    TYPE_FILE ->
                        if (chosen.includes(entry.path)) toMediaItem(entry)?.let(collected::add)
                }
            }
        }

        if (collected.size >= MAX_ITEMS) {
            Log.w(TAG, "обход остановлен на пределе в $MAX_ITEMS элементов")
        }
        onProgress(collected.size, -1)
        collected
    }

    override suspend fun firstShowable(): MediaItem? = withContext(Dispatchers.IO) {
        val chosen = selection()
        val queue = ArrayDeque(listOf(ROOT_PATH to 0))
        val visited = mutableSetOf(ROOT_PATH)

        while (queue.isNotEmpty()) {
            val (path, depth) = queue.removeFirst()
            for (entry in listFolder(path)) {
                when (entry.type) {
                    TYPE_FILE -> {
                        if (!chosen.includes(entry.path)) continue
                        val item = toMediaItem(entry)
                        if (item != null && item.isShowable && item.kind == MediaKind.PHOTO) {
                            return@withContext item
                        }
                    }

                    TYPE_DIR ->
                        if (depth < MAX_DEPTH &&
                            chosen.shouldDescend(entry.path) &&
                            visited.add(entry.path)
                        ) {
                            queue.addLast(entry.path to depth + 1)
                        }
                }
            }
        }
        null
    }

    override suspend fun allFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Folder>()
        val queue = ArrayDeque(listOf(ROOT_PATH to 0))
        val visited = mutableSetOf(ROOT_PATH)

        while (queue.isNotEmpty() && found.size < MAX_FOLDERS) {
            val (path, depth) = queue.removeFirst()
            for (entry in listFolder(path)) {
                if (entry.type != TYPE_DIR) continue
                found += Folder(name = entry.name, path = entry.path)
                if (depth < MAX_DEPTH && visited.add(entry.path)) {
                    queue.addLast(entry.path to depth + 1)
                }
            }
            // Пауза между папками: список нужен раз в неделю, спешить некуда,
            // а хранилищу спокойнее.
            delay(FOLDER_SCAN_PAUSE_MILLIS)
        }
        found
    }

    override suspend fun subfolders(path: String): List<Folder> = withContext(Dispatchers.IO) {
        listFolder(path)
            .filter { it.type == TYPE_DIR }
            .map { Folder(name = it.name, path = it.path) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun downloadUrl(item: MediaItem): String = withContext(Dispatchers.IO) {
        val url = downloadBase.newBuilder()
            .addQueryParameter("public_key", publicKey)
            .addQueryParameter("path", item.path)
            .build()

        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ссылка на ${item.path} вернула ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("пустой ответ на ссылку")
            json.decodeFromString(DownloadLinkDto.serializer(), body).href
        }
    }

    override suspend fun refresh(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        val url = apiBase.newBuilder()
            .addQueryParameter("public_key", publicKey)
            .addQueryParameter("path", item.path)
            .addQueryParameter("preview_size", PreviewSize.FULL.apiValue)
            .addQueryParameter("preview_crop", "false")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw IOException("сведения о «${item.path}» вернули ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("пустой ответ о «${item.path}»")
            // Ответ об одном файле устроен так же, как элемент листинга.
            toMediaItem(json.decodeFromString(ResourceItemDto.serializer(), body))
        }
    }

    /** Одна папка целиком: страницы запрашиваются, пока не кончатся элементы. */
    private fun listFolder(path: String): List<ResourceItemDto> {
        val entries = mutableListOf<ResourceItemDto>()
        var offset = 0

        while (true) {
            val page = requestPage(path, offset)
            val items = page.embedded?.items.orEmpty()
            entries += items

            offset += items.size
            val total = page.embedded?.total ?: 0
            // Страница короче запрошенной или собрали заявленное — папка кончилась.
            if (items.isEmpty() || items.size < pageLimit || offset >= total) break
        }
        return entries
    }

    private fun requestPage(path: String, offset: Int): PublicResourceDto {
        val url = apiBase.newBuilder()
            .addQueryParameter("public_key", publicKey)
            .addQueryParameter("path", path)
            .addQueryParameter("limit", pageLimit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("preview_size", PreviewSize.FULL.apiValue)
            .addQueryParameter("preview_crop", "false")
            .build()

        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("листинг «$path» вернул ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("пустой ответ листинга «$path»")
            return json.decodeFromString(PublicResourceDto.serializer(), body)
        }
    }

    private fun toMediaItem(dto: ResourceItemDto): MediaItem? {
        val kind = when (dto.mediaType) {
            MEDIA_TYPE_IMAGE -> MediaKind.PHOTO
            MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
            else -> return null
        }

        // Превью генерирует хранилище, поэтому формат оригинала значения не имеет.
        // Если превью не отдано, элемент всё равно попадает в индекс — просто
        // помеченным как непоказываемый.
        val preview = dto.preview?.let(PreviewUrl::fromApiUrl)
        if (preview == null) {
            Log.d(TAG, "нечем показать ${dto.path}: превью не отдано")
        }

        return MediaItem(
            path = dto.path,
            name = dto.name,
            kind = kind,
            mimeType = dto.mimeType,
            sizeBytes = dto.size,
            takenAtMillis = parseTimestamp(dto.exif?.dateTime),
            addedAtMillis = parseTimestamp(dto.created ?: dto.modified),
            preview = preview,
        )
    }

    private fun parseTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            TIMESTAMP_FORMAT.get()?.parse(raw)?.time
        } catch (e: java.text.ParseException) {
            Log.d(TAG, "не разобрал дату «$raw»", e)
            null
        }
    }

    companion object {
        private const val TAG = "YaPhotoFrame"
        private const val ROOT_PATH = "/"
        private const val TYPE_FILE = "file"
        private const val TYPE_DIR = "dir"
        private const val MEDIA_TYPE_IMAGE = "image"
        private const val MEDIA_TYPE_VIDEO = "video"

        const val DEFAULT_PAGE_LIMIT = 200

        /** Защита от вложенности, из которой не выбраться. */
        private const val MAX_DEPTH = 12

        /** Потолок на список папок: дерево из десятков тысяч узлов не выбрать всё равно. */
        private const val MAX_FOLDERS = 5_000

        /** Пауза между обращениями при сборе списка папок. */
        private const val FOLDER_SCAN_PAUSE_MILLIS = 120L

        /** Потолок на случай, если в папку положили что-то неожиданно огромное. */
        /**
         * Потолок обхода. Каждая запись индекса — это ещё и подписанная ссылка
         * на превью в несколько сотен символов; пятьдесят тысяч записей не
         * помещались в кучу телевизора вместе с картинками. Двадцать тысяч —
         * с запасом больше любого разумного альбома; на Диске побольше
         * отмечают подпапки.
         */
        private const val MAX_ITEMS = 20_000

        // SimpleDateFormat не потокобезопасен, а листинг разбирается в фоновом потоке.
        private val TIMESTAMP_FORMAT: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() =
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
            }

        val DEFAULT_API_BASE: HttpUrl =
            "https://cloud-api.yandex.net/v1/disk/public/resources".toHttpUrl()
    }
}
