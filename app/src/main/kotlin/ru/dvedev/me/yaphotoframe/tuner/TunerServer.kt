package ru.dvedev.me.yaphotoframe.tuner

import android.content.res.AssetManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.dvedev.me.yaphotoframe.BuildConfig
import ru.dvedev.me.yaphotoframe.settings.SettingsStore
import ru.dvedev.me.yaphotoframe.ui.FrameSettings
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Крошечный веб-сервер для подбора визуала с телефона.
 *
 * Написан вручную, без библиотеки: нужны ровно три ответа, а лишняя зависимость
 * на устройстве с двумя гигабайтами памяти не окупается.
 *
 * Смысл его существования — скорость обратной связи. Визуальный стиль
 * подбирается десятками итераций, и цикл «двинул ползунок — увидел на экране»
 * должен занимать мгновение, а не пересборку.
 */
/** Адрес страницы вместе с интерфейсом, на котором она видна. */
data class TunerAddress(val interfaceName: String, val url: String)

class TunerServer(
    private val store: SettingsStore,
    private val assets: AssetManager,
    private val port: Int = DEFAULT_PORT,
    /** Что показать на вкладке состояния: готовый JSON от того, кто это знает. */
    private val diagnostics: () -> String = { "{}" },
    /** Обойти папку прямо сейчас, не дожидаясь срока. */
    private val onRefresh: () -> Unit = {},
    /** Подпапки указанного пути — готовым JSON от того, кто умеет их спросить. */
    private val folders: (String) -> String = { "[]" },
    /** Пересобрать список папок — он собирается редко и неспешно. */
    private val onRescanFolders: () -> Unit = {},
    /**
     * Кто поднял страницу: «dream» — заставка, «app» — экран приложения.
     * Странице это важно: из приложения индекс не строится и показ не идёт,
     * и подсказывать надо другое.
     */
    private val host: String = "dream",
    /** Тома под флешку — страница показывает их списком. */
    private val storage: () -> String = { "{\"volumes\":[]}" },
    /** Есть ли том с таким UUID на этом телевизоре — для переноса настроек. */
    private val hasVolume: (String) -> Boolean = { true },
) {

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    /**
     * Небольшой пул на обслуживание запросов.
     *
     * Браузер открывает несколько соединений сразу, а страница ещё и опрашивает
     * состояние. Обслуживание по одному в потоке приёма заставляло бы их ждать
     * друг друга, а зависший клиент подвешивал бы весь тюнер.
     */
    private val workers: ExecutorService = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
        Thread(runnable, "tuner-worker").apply { isDaemon = true }
    }

    /**
     * Порт после переустановки ещё с полминуты занят прежним процессом:
     * заставка поднимается раньше, чем система отпустит его. Пробуем
     * несколько раз, иначе страница до следующего запуска молчала бы.
     */
    private fun bind(): ServerSocket {
        var attempt = 0
        while (!closing) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
            } catch (e: java.net.BindException) {
                // Сколько бы ни ждать: страница без тюнера молчит до следующего
                // запуска заставки, а порт рано или поздно освободится.
                attempt++
                if (attempt % BIND_LOG_EVERY == 1) Log.w(TAG, "порт $port занят, попытка $attempt")
                try {
                    Thread.sleep(BIND_RETRY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        }
        throw IOException("тюнер остановлен, не дождавшись порта $port")
    }

    @Volatile
    private var closing = false

    fun start() {
        if (worker != null) return
        closing = false
        worker = thread(name = "tuner-server", isDaemon = true) {
            try {
                bind().use { socket ->
                    serverSocket = socket
                    Log.i(TAG, "тюнер доступен на ${addresses().joinToString()}")
                    while (!socket.isClosed) {
                        val client = try {
                            socket.accept()
                        } catch (e: IOException) {
                            break // сокет закрыли — это штатное завершение
                        }
                        // Каждое соединение обслуживается отдельно и в своей
                        // ловушке. Раньше исключение из обработчика вылетало из
                        // цикла и убивало сервер навсегда: достаточно было
                        // одного оборванного запроса — закрыли вкладку, отвалился
                        // Wi-Fi, — и страница переставала отвечать до перезапуска
                        // заставки. Для вещи, которая живёт в проде, это негодно.
                        workers.execute {
                            try {
                                client.use(::handle)
                            } catch (e: Exception) {
                                Log.d(TAG, "запрос к тюнеру оборвался", e)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "тюнер не поднялся на порту $port", e)
            } finally {
                serverSocket = null
            }
        }
    }

    fun stop() {
        closing = true
        workers.shutdownNow()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.d(TAG, "тюнер уже закрыт", e)
        }
        // Ждёт порт — разбудить, чтобы вышел.
        worker?.interrupt()
        worker = null
    }

    /**
     * Адреса, по которым страница доступна, — сначала самый вероятный.
     *
     * У телевизора их бывает несколько: провод, Wi-Fi, а если поднят VPN — то и
     * его туннель. Туннель отсекается по признаку «точка-точка»: это надёжнее
     * догадок по имени, а показывать адрес внутри VPN как основной — вернейший
     * способ, чтобы страница «не открывалась».
     *
     * Остальные не выбрасываются: у кого-то телевизор подключён и проводом, и
     * по воздуху, и знать второй адрес полезно.
     */
    fun addresses(): List<TunerAddress> = NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }
        .flatMap { network ->
            network.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .map { TunerAddress(network.name, "http://${it.hostAddress}:$port") }
        }
        .sortedBy { priorityOf(it.interfaceName) }
        .toList()

    /** Провод считаем основным, за ним Wi-Fi, остальное — потом. */
    private fun priorityOf(name: String): Int = when {
        name.startsWith("eth") -> 0
        name.startsWith("wlan") -> 1
        else -> 2
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")

        var contentLength = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(bytes, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }

        try {
            route(client, method, path, query, body)
        } catch (e: Exception) {
            // Ошибка в обработке одного запроса не должна оставлять клиента без
            // ответа: пустой ответ выглядит как обрыв сети и уводит поиски
            // причины совсем не туда.
            Log.w(TAG, "не смог обработать $method $path", e)
            respond(client, "500 Internal Server Error", "text/plain; charset=utf-8", "не вышло")
        }
    }

    private fun route(
        client: Socket,
        method: String,
        path: String,
        query: String,
        body: String,
    ) {
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                respond(client, "200 OK", "text/html; charset=utf-8", page())

            method == "GET" && path == "/api/settings" ->
                respond(client, "200 OK", "application/json; charset=utf-8", json(store.current))

            // Значения по умолчанию: странице они нужны, чтобы у каждого
            // ползунка была своя кнопка «вернуть» и чтобы гасить её, когда
            // возвращать нечего.
            method == "GET" && path == "/api/defaults" ->
                respond(client, "200 OK", "application/json; charset=utf-8", json(FrameSettings()))

            method == "POST" && path == "/api/settings" -> {
                apply(body)
                respond(client, "200 OK", "application/json; charset=utf-8", json(store.current))
            }

            method == "GET" && path == "/api/folders" ->
                respond(client, "200 OK", "application/json; charset=utf-8", folders(query))

            method == "GET" && path == "/api/storage" ->
                respond(client, "200 OK", "application/json; charset=utf-8", storage())

            method == "GET" && path == "/api/state" ->
                respond(client, "200 OK", "application/json; charset=utf-8", diagnostics())

            // Стеки всех потоков: релизную сборку снаружи не продампить, а
            // рамка однажды встала на одном снимке и молчала.
            method == "GET" && path == "/api/threads" ->
                respond(client, "200 OK", "text/plain; charset=utf-8", ThreadDump.text())

            method == "POST" && path == "/api/rescan-folders" -> {
                onRescanFolders()
                respond(client, "200 OK", "application/json; charset=utf-8", "{\"ok\":true}")
            }

            method == "POST" && path == "/api/refresh" -> {
                onRefresh()
                respond(client, "200 OK", "application/json; charset=utf-8", "{\"ok\":true}")
            }

            // Перенос настроек на другой телевизор: страница отдаёт тот же JSON,
            // что и /api/settings, а здесь он принимается назад целиком.
            method == "POST" && path == "/api/import" -> {
                val problem = importJson(body)
                if (problem == null) {
                    respond(client, "200 OK", "application/json; charset=utf-8", json(store.current))
                } else {
                    respond(client, "400 Bad Request", "text/plain; charset=utf-8", problem)
                }
            }

            method == "POST" && path == "/api/reset" -> {
                store.reset()
                respond(client, "200 OK", "application/json; charset=utf-8", json(store.current))
            }

            method == "GET" && path in STATIC_FILES ->
                serveAsset(client, path.removePrefix("/"))

            else -> respond(client, "404 Not Found", "text/plain; charset=utf-8", "нет такой страницы")
        }
    }

    /** Разбирает JSON настроек в тот же вид, что присылает форма, и применяет. Null — успех. */
    private fun importJson(body: String): String? {
        val fields = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return "это не JSON настроек"
        }
        val values = fields.mapNotNull { (key, value) ->
            if (key == "host" || key == "version") return@mapNotNull null
            val raw = when (value) {
                is JsonArray -> value.joinToString("\n") { (it as? JsonPrimitive)?.content ?: "" }
                is JsonPrimitive -> value.content
                else -> return@mapNotNull null
            }
            key to raw
        }.toMap()
        if ("folderUrl" !in values) return "в файле нет настроек рамки"
        val filtered = ImportFilter.filter(values, hasVolume)
        if (filtered.size != values.size) {
            ru.dvedev.me.yaphotoframe.diag.Diary.note(
                "перенос настроек: флешки ${ImportFilter.chosenVolume(values)} на этом телевизоре нет, место хранилища оставлено прежним",
            )
        }
        apply(filtered.entries.joinToString("&") { (key, raw) ->
            key + "=" + java.net.URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
        })
        return null
    }

    private fun apply(body: String) {
        val values = body.split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=', "")
                val raw = pair.substringAfter('=', "")
                if (name.isEmpty()) null
                else name to URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            }
            .toMap()
        if (values.isEmpty()) return

        store.update { current ->
            FrameSettings(
                folderUrl = values["folderUrl"] ?: current.folderUrl,
                showDurationMillis = values["showDurationMillis"]?.toLongOrNull()
                    ?: current.showDurationMillis,
                crossfadeMillis = values["crossfadeMillis"]?.toLongOrNull()
                    ?: current.crossfadeMillis,
                driftAmplitude = values["driftAmplitude"]?.toFloatOrNull()
                    ?: current.driftAmplitude,
                zoomAmount = values["zoomAmount"]?.toFloatOrNull() ?: current.zoomAmount,
                frameInsetLandscape = (values["frameInsetLandscape"] ?: values["frameInset"])?.toFloatOrNull()
                    ?: current.frameInsetLandscape,
                frameInsetPortrait = values["frameInsetPortrait"]?.toFloatOrNull()
                    ?: current.frameInsetPortrait,
                edgeMargin = values["edgeMargin"]?.toFloatOrNull() ?: current.edgeMargin,
                placementStrength = values["placementStrength"]?.toFloatOrNull()
                    ?: current.placementStrength,
                backgroundDim = values["backgroundDim"]?.toFloatOrNull() ?: current.backgroundDim,
                blurSampleLongSide = values["blurSampleLongSide"]?.toIntOrNull()
                    ?: current.blurSampleLongSide,
                tunerEnabled = values["tunerEnabled"]?.toBooleanStrictOrNull()
                    ?: current.tunerEnabled,
                storageVolumeUuid = values["storageVolumeUuid"] ?: values["externalStorageUuid"]
                    ?: current.storageVolumeUuid,
                storageBytes = values["storageBytes"]?.toLongOrNull() ?: current.storageBytes,
                storageByFree = values["storageByFree"]?.toBooleanStrictOrNull() ?: current.storageByFree,
                storageReserveBytes = (values["storageReserveBytes"] ?: values["externalReserveBytes"])?.toLongOrNull()
                    ?: current.storageReserveBytes,
                minStorePhotoBytes = values["minStorePhotoBytes"]?.toLongOrNull() ?: current.minStorePhotoBytes,
                minStoreVideoBytes = values["minStoreVideoBytes"]?.toLongOrNull() ?: current.minStoreVideoBytes,
                maxFileBytes = (values["maxFileBytes"] ?: values["videoMaxSizeBytes"])?.toLongOrNull()
                    ?: current.maxFileBytes,
                networkBps = values["networkBps"]?.toLongOrNull() ?: current.networkBps,
                prefetchCount = values["prefetchCount"]?.toIntOrNull() ?: current.prefetchCount,
                indexRefreshIntervalMillis = values["indexRefreshIntervalMillis"]?.toLongOrNull()
                    ?: current.indexRefreshIntervalMillis,
                showVideo = values["showVideo"]?.toBooleanStrictOrNull() ?: current.showVideo,
                downloadsDuringVideo = values["downloadsDuringVideo"]?.toBooleanStrictOrNull()
                    ?: current.downloadsDuringVideo,
                videoMaxDurationMillis = values["videoMaxDurationMillis"]?.toLongOrNull()
                    ?: current.videoMaxDurationMillis,
                videoSoundEnabled = values["videoSoundEnabled"]?.toBooleanStrictOrNull()
                    ?: current.videoSoundEnabled,
                pairPortraits = values["pairPortraits"]?.toBooleanStrictOrNull()
                    ?: current.pairPortraits,
                freshnessWindowDays = values["freshnessWindowDays"]?.toIntOrNull()
                    ?: current.freshnessWindowDays,
                minPhotoFraction = values["minPhotoFraction"]?.toFloatOrNull()
                    ?: current.minPhotoFraction,
                showClock = values["showClock"]?.toBooleanStrictOrNull() ?: current.showClock,
                pauseAutoResumeMillis = values["pauseAutoResumeMillis"]?.toLongOrNull()
                    ?: current.pauseAutoResumeMillis,
                showDate = values["showDate"]?.toBooleanStrictOrNull() ?: current.showDate,
                // Пустое значение — законный выбор «вся папка целиком»,
                // поэтому отличаем отсутствие ключа от пустой строки.
                // Пути не подрезаем: у папки на Диске может быть пробел на
                // конце имени, и после trim она не выбиралась никогда.
                selectedFolders = values["selectedFolders"]
                    ?.split('\n')
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: current.selectedFolders,
            )
        }
    }

    private fun json(settings: FrameSettings): String = buildString {
        append('{')
        append("\"host\":\"").append(host).append("\",")
        append("\"version\":\"").append(BuildConfig.VERSION_NAME).append("\"")
        for ((key, value) in settings.asMap()) {
            append(",\"").append(key).append("\":")
            when (value) {
                is String -> append('"').append(value.replace("\"", "")).append('"')
                is Set<*> -> append(
                    value.joinToString(",", "[", "]") { "\"" + it.toString().replace("\"", "") + "\"" },
                )
                else -> append(value.toString())
            }
        }
        append('}')
    }

    /**
     * Отдаёт файл из ресурсов приложения как есть.
     *
     * Иконки — двоичные, поэтому идут байтами: пропустить их через строку
     * значило бы испортить.
     */
    private fun serveAsset(client: Socket, name: String) {
        val bytes = assets.open(name).use { it.readBytes() }
        val type = when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".json") -> "application/json; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
        respondBytes(client, "200 OK", type, bytes)
    }

    private fun page(): String =
        assets.open(PAGE_ASSET).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun respond(client: Socket, status: String, contentType: String, body: String) =
        respondBytes(client, status, contentType, body.toByteArray(StandardCharsets.UTF_8))

    private fun respondBytes(
        client: Socket,
        status: String,
        contentType: String,
        bytes: ByteArray,
    ) {
        client.getOutputStream().apply {
            write(
                buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(StandardCharsets.UTF_8),
            )
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(byte.toChar())
        }
    }

    companion object {
        const val DEFAULT_PORT = 8099
        private const val WORKER_THREADS = 4
        private const val BIND_LOG_EVERY = 15
        private const val BIND_RETRY_MILLIS = 4_000L
        private const val TAG = "YaPhotoFrame"
        private const val PAGE_ASSET = "tuner.html"

        /** Что отдаётся из ресурсов как есть: тексты настроек и оболочка приложения на телефоне. */
        private val STATIC_FILES = setOf(
            "/settings-ui.json",
            "/manifest.json",
            "/sw.js",
            "/icon-192.png",
            "/icon-512.png",
            "/icon-maskable-512.png",
            "/apple-touch-icon.png",
        )
    }
}
