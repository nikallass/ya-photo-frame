package ru.dvedev.me.yaphotoframe.tuner

import android.content.res.AssetManager
import android.util.Log
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

    fun start() {
        if (worker != null) return
        worker = thread(name = "tuner-server", isDaemon = true) {
            try {
                ServerSocket(port).use { socket ->
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
        workers.shutdownNow()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.d(TAG, "тюнер уже закрыт", e)
        }
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

            method == "POST" && path == "/api/settings" -> {
                apply(body)
                respond(client, "200 OK", "application/json; charset=utf-8", json(store.current))
            }

            method == "GET" && path == "/api/folders" ->
                respond(client, "200 OK", "application/json; charset=utf-8", folders(query))

            method == "GET" && path == "/api/state" ->
                respond(client, "200 OK", "application/json; charset=utf-8", diagnostics())

            method == "POST" && path == "/api/rescan-folders" -> {
                onRescanFolders()
                respond(client, "200 OK", "application/json; charset=utf-8", "{\"ok\":true}")
            }

            method == "POST" && path == "/api/refresh" -> {
                onRefresh()
                respond(client, "200 OK", "application/json; charset=utf-8", "{\"ok\":true}")
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
                driftSpeedPerMinute = values["driftSpeedPerMinute"]?.toFloatOrNull()
                    ?: current.driftSpeedPerMinute,
                frameInset = values["frameInset"]?.toFloatOrNull() ?: current.frameInset,
                edgeMargin = values["edgeMargin"]?.toFloatOrNull() ?: current.edgeMargin,
                placementStrength = values["placementStrength"]?.toFloatOrNull()
                    ?: current.placementStrength,
                backgroundDim = values["backgroundDim"]?.toFloatOrNull() ?: current.backgroundDim,
                blurSampleLongSide = values["blurSampleLongSide"]?.toIntOrNull()
                    ?: current.blurSampleLongSide,
                tunerEnabled = values["tunerEnabled"]?.toBooleanStrictOrNull()
                    ?: current.tunerEnabled,
                cacheBudgetBytes = values["cacheBudgetBytes"]?.toLongOrNull()
                    ?: current.cacheBudgetBytes,
                cacheItemThresholdBytes = values["cacheItemThresholdBytes"]?.toLongOrNull()
                    ?: current.cacheItemThresholdBytes,
                prefetchCount = values["prefetchCount"]?.toIntOrNull() ?: current.prefetchCount,
                indexRefreshIntervalMillis = values["indexRefreshIntervalMillis"]?.toLongOrNull()
                    ?: current.indexRefreshIntervalMillis,
                showVideo = values["showVideo"]?.toBooleanStrictOrNull() ?: current.showVideo,
                videoMaxDurationMillis = values["videoMaxDurationMillis"]?.toLongOrNull()
                    ?: current.videoMaxDurationMillis,
                videoSoundEnabled = values["videoSoundEnabled"]?.toBooleanStrictOrNull()
                    ?: current.videoSoundEnabled,
                pairPortraits = values["pairPortraits"]?.toBooleanStrictOrNull()
                    ?: current.pairPortraits,
                freshnessWindowDays = values["freshnessWindowDays"]?.toIntOrNull()
                    ?: current.freshnessWindowDays,
                showClock = values["showClock"]?.toBooleanStrictOrNull() ?: current.showClock,
                showDate = values["showDate"]?.toBooleanStrictOrNull() ?: current.showDate,
                // Пустое значение — законный выбор «вся папка целиком»,
                // поэтому отличаем отсутствие ключа от пустой строки.
                selectedFolders = values["selectedFolders"]
                    ?.split('\n')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: current.selectedFolders,
            )
        }
    }

    private fun json(settings: FrameSettings): String = buildString {
        append('{')
        append("\"folderUrl\":\"").append(settings.folderUrl.replace("\"", "")).append("\",")
        append("\"showDurationMillis\":").append(settings.showDurationMillis).append(',')
        append("\"crossfadeMillis\":").append(settings.crossfadeMillis).append(',')
        append("\"driftAmplitude\":").append(settings.driftAmplitude).append(',')
        append("\"driftSpeedPerMinute\":").append(settings.driftSpeedPerMinute).append(',')
        append("\"frameInset\":").append(settings.frameInset).append(',')
        append("\"edgeMargin\":").append(settings.edgeMargin).append(',')
        append("\"placementStrength\":").append(settings.placementStrength).append(',')
        append("\"backgroundDim\":").append(settings.backgroundDim).append(',')
        append("\"blurSampleLongSide\":").append(settings.blurSampleLongSide).append(',')
        append("\"tunerEnabled\":").append(settings.tunerEnabled).append(',')
        append("\"cacheBudgetBytes\":").append(settings.cacheBudgetBytes).append(',')
        append("\"cacheItemThresholdBytes\":").append(settings.cacheItemThresholdBytes).append(',')
        append("\"prefetchCount\":").append(settings.prefetchCount).append(',')
        append("\"indexRefreshIntervalMillis\":").append(settings.indexRefreshIntervalMillis)
        append(',')
        append("\"showVideo\":").append(settings.showVideo).append(',')
        append("\"videoMaxDurationMillis\":").append(settings.videoMaxDurationMillis).append(',')
        append("\"videoSoundEnabled\":").append(settings.videoSoundEnabled).append(',')
        append("\"pairPortraits\":").append(settings.pairPortraits).append(',')
        append("\"freshnessWindowDays\":").append(settings.freshnessWindowDays).append(',')
        append("\"showClock\":").append(settings.showClock).append(',')
        append("\"showDate\":").append(settings.showDate).append(',')
        append("\"selectedFolders\":").append(
            settings.selectedFolders.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" },
        )
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
        private const val TAG = "YaPhotoFrame"
        private const val PAGE_ASSET = "tuner.html"

        /** Что отдаётся из ресурсов как есть: оболочка приложения на телефоне. */
        private val STATIC_FILES = setOf(
            "/manifest.json",
            "/sw.js",
            "/icon-192.png",
            "/icon-512.png",
            "/icon-maskable-512.png",
            "/apple-touch-icon.png",
        )
    }
}
