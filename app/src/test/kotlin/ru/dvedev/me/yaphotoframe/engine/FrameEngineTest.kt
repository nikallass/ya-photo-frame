package ru.dvedev.me.yaphotoframe.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.dvedev.me.yaphotoframe.cache.CachePolicy
import ru.dvedev.me.yaphotoframe.cache.Delivery
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.cache.ArchiveStore
import ru.dvedev.me.yaphotoframe.video.DurationProber
import ru.dvedev.me.yaphotoframe.library.FolderIndexStore
import ru.dvedev.me.yaphotoframe.library.LibraryStore
import ru.dvedev.me.yaphotoframe.media.FolderSelection
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import ru.dvedev.me.yaphotoframe.media.yandex.YandexPublicDiskSource
import java.io.File
import kotlin.random.Random

/**
 * Проверки движка через единственный шов — [FrameEngine].
 *
 * Хранилище подменено локальным сервером, отдающим записанные ответы настоящего
 * API, время задано снаружи. Проверяется только то, что видно снаружи: что
 * попало в библиотеку, что из этого можно показать, что рамка о показах помнит.
 * Разбор ответов, обход дерева и запись на диск отдельно не проверяются — они
 * наблюдаются через эти же утверждения.
 */
class FrameEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var indexFile: File
    private lateinit var cacheDirectory: File
    private var policy = CachePolicy(prefetchCount = LOOKAHEAD)

    /** Пути, для которых сервер отдаёт ответ. Тест может убрать любой из них. */
    private val available = linkedMapOf(
        "/" to listOf("tree-root-0.json", "tree-root-2.json"),
        "/Отпуск" to listOf("tree-otpusk-0.json", "tree-otpusk-2.json"),
        "/Дети" to listOf("tree-deti-0.json"),
        "/Дети/2019" to listOf("tree-deti-2019-0.json"),
    )

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        indexFile = File(temporaryFolder.newFolder(), "library.json")
        cacheDirectory = temporaryFolder.newFolder("cache")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when {
                    url.encodedPath.endsWith("/resources/download") -> {
                        // Адрес файла несёт нужный размер: подставное хранилище
                        // отдаст ровно столько байт, сколько просят.
                        val href = server.url("/file/" + BYTES_PER_ORIGINAL)
                        MockResponse().setBody("{\"href\":\"" + href + "\"}")
                    }

                    url.encodedPath.startsWith("/file/") ->
                        bytes(url.pathSegments.last().toInt())

                    url.encodedPath.startsWith("/preview/") -> when {
                        offline -> MockResponse().setResponseCode(503)
                        // Погасшая подпись: хранилище отвечает 410, пока ссылку
                        // не спросят заново.
                        url.queryParameter("filename") in expired &&
                            !url.encodedPath.contains("/fresh/") -> MockResponse().setResponseCode(410)

                        else -> bytes(
                            if (url.queryParameter("size") == "S") BYTES_PER_MICRO
                            else BYTES_PER_FULL
                        )
                    }

                    else -> listing(url)
                }
            }
        }
        server.start()
    }

    private fun bytes(count: Int) = MockResponse().setBody(Buffer().write(ByteArray(count)))

    /** Имена файлов, чьи ссылки на превью из индекса уже погасли. */
    private val expired = mutableSetOf<String>()

    /** Сеть легла: превью не отдаются вовсе. */
    private var offline = false

    private fun listing(url: okhttp3.HttpUrl): MockResponse {
        val path = url.queryParameter("path") ?: "/"
        val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
        val limit = url.queryParameter("limit")?.toIntOrNull() ?: 1
        val pages = available[path] ?: return MockResponse().setResponseCode(404)
        val page = pages.getOrNull(offset / limit) ?: return MockResponse().setResponseCode(404)
        return MockResponse().setBody(fixture(page))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `обход собирает файлы из вложенных папок`() = runTest {
        val outcome = library().sync()

        assertEquals("всего файлов во всём дереве", 7, outcome.total)
        assertEquals("фотографий", 6, outcome.photos)
        assertEquals("видео индексируются наравне с фотографиями", 1, outcome.videos)
    }

    @Test
    fun `файл без превью остаётся в библиотеке, но показать его нечем`() = runTest {
        val library = library()
        val outcome = library.sync()

        assertEquals("непоказываемых", 1, outcome.unshowable)

        val broken = library.entries.single { it.item.name == "битый.raf" }
        assertFalse("превью нет — показывать нечем", broken.item.isShowable)
        assertTrue(
            "и в список для показа он не попадает",
            library.showablePhotos().none { it.item.name == "битый.raf" },
        )
        assertEquals("а показать можно всё остальное", 5, library.showablePhotos().size)
    }

    @Test
    fun `дата съёмки берётся из EXIF, а не из времени загрузки`() = runTest {
        val library = library()
        library.sync()

        val entry = library.entries.single { it.item.name == "море1.jpg" }
        assertEquals(1_628_942_400_000L, entry.item.takenAtMillis)
    }

    @Test
    fun `свежесть считается по дате заливки, а не по дате съёмки`() = runTest {
        val library = library()
        library.sync()

        // У снимков из соцсетей EXIF нет вовсе, но дата появления в хранилище
        // есть всегда — именно она и означает свежесть.
        val withoutExif = library.entries.single { it.item.name == "битый.raf" }
        assertNull("даты съёмки нет", withoutExif.item.takenAtMillis)
        assertNotNull("а дата заливки есть", withoutExif.item.addedAtMillis)

        // И у снимка с EXIF это разные даты: снят в 2021-м, залит позже.
        val withExif = library.entries.single { it.item.name == "море1.jpg" }
        assertTrue(
            "залит позже, чем снят",
            withExif.item.addedAtMillis!! > withExif.item.takenAtMillis!!,
        )
    }

    @Test
    fun `индекс переживает перезапуск приложения`() = runTest {
        library().sync()

        // Новый экземпляр поднимает индекс с диска. Сервер при этом не трогается:
        // если бы библиотека полезла в сеть, диспетчер отдал бы те же данные и
        // проверка ничего не значила бы — поэтому сервер здесь уже остановлен.
        server.shutdown()
        val restarted = library()

        assertEquals(7, restarted.entries.size)
        assertEquals(5, restarted.showablePhotos().size)
        assertTrue("время последнего обхода тоже сохранено", restarted.syncedAtMillis > 0)
    }

    @Test
    fun `память о показах переживает следующий обход`() = runTest {
        val engine = library()
        engine.sync()

        now = 1_700_000_500_000L
        val shown = checkNotNull(engine.advance())

        now = 1_700_000_900_000L
        engine.sync()

        val entry = engine.entries.single { it.item.path == shown.path }
        assertEquals(1_700_000_500_000L, entry.lastShownAtMillis)
        assertEquals(
            "отметка стоит ровно у показанного",
            1,
            engine.entries.count { it.lastShownAtMillis != null },
        )
    }

    @Test
    fun `удалённая на хранилище папка исчезает из библиотеки`() = runTest {
        val library = library()
        library.sync()
        assertNotNull(library.entries.find { it.item.path == "/Дети/2019/сад.jpg" })

        // Папку «Дети» удалили: корень о ней больше не сообщает.
        available["/"] = listOf("tree-root-0.json", "tree-root-empty.json")
        val outcome = library.sync()

        assertEquals("ушли и сама папка, и всё её содержимое", 2, outcome.removed)
        assertTrue(library.entries.none { it.item.path.startsWith("/Дети") })
        assertEquals(5, outcome.total)
    }

    @Test
    fun `испорченный индекс не мешает рамке запуститься`() = runTest {
        indexFile.parentFile.mkdirs()
        indexFile.writeText("{это не json")

        val library = library()

        assertTrue("библиотека поднялась пустой", library.entries.isEmpty())
        assertEquals("и готова обойти папку заново", 7, library.sync().total)
    }

    // ── порядок показа ──────────────────────────────────────────────────────

    @Test
    fun `очередь строится на несколько кадров вперёд и без повторов внутри себя`() = runTest {
        switchToBulkFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        val upcoming = engine.upcoming()

        assertEquals("столько, на сколько смотрим вперёд", LOOKAHEAD, upcoming.size)
        assertEquals(
            "внутри окна предзагрузки один кадр не встречается дважды",
            upcoming.size,
            upcoming.map { it.path }.toSet().size,
        )
    }

    @Test
    fun `недавно показанное не возвращается, пока не показано остальное`() = runTest {
        switchToBulkFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        val shown = mutableListOf<String>()
        repeat(10) {
            shown += checkNotNull(engine.advance()).path
            now += 60_000
        }

        // Половина библиотеки под запретом, значит в десяти показах подряд
        // повторов быть не должно вовсе.
        assertEquals("повторов нет", shown.size, shown.toSet().size)
    }

    @Test
    fun `недавно добавленное показывается заметно чаще давнего`() = runTest {
        switchToBulkFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        var fresh = 0
        repeat(40) {
            if (checkNotNull(engine.advance()).name.startsWith("new")) fresh++
            now += 60_000
        }

        // Свежих в папке четверть, но бонус свежести поднимает их долю.
        // Точное число зависит от зерна, поэтому проверяется порядок величины.
        assertTrue("свежих должно быть больше четверти, а вышло $fresh из 40", fresh > 12)
    }

    @Test
    fun `остывание не выкашивает папку, которая идёт первой по порядку`() = runTest {
        switchToBulkFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        // Никто ещё не показан. Первые пятнадцать по порядку обхода — «old»,
        // последние пять — «new». Раньше запрет на половину пула считался по
        // одинаковому «никогда» и молча снимал с выбора первые десять из
        // пятнадцати «old»: перекос к хвосту списка, то есть к одной папке.
        val firstPicks = engine.upcoming().map { it.name }

        assertTrue(
            "среди первых же кадров должны быть снимки из начала списка, а выбраны: $firstPicks",
            firstPicks.any { it.startsWith("old0") },
        )
    }

    @Test
    fun `порядок воспроизводим при том же зерне и различается при другом`() = runTest {
        switchToBulkFolder()

        val first = library(pageLimit = 50, seed = 42).run { sync(); upcoming().map { it.path } }
        indexFile.delete()
        val same = library(pageLimit = 50, seed = 42).run { sync(); upcoming().map { it.path } }
        indexFile.delete()
        val other = library(pageLimit = 50, seed = 7).run { sync(); upcoming().map { it.path } }

        assertEquals("то же зерно — тот же порядок", first, same)
        assertTrue("другое зерно — другой порядок", first != other)
    }

    @Test
    fun `видео в очередь показа не попадают`() = runTest {
        val engine = library()
        engine.sync()

        val queued = engine.upcoming().map { it.name }
        assertTrue("роликов в очереди нет", queued.none { it.endsWith(".mp4") })
        assertTrue("а в индексе они есть", engine.entries.any { it.item.kind == MediaKind.VIDEO })
    }

    @Test
    fun `видео берутся в очередь, когда флаг включён`() = runTest {
        val engine = library(includeVideo = true)
        engine.sync()

        val seen = buildSet { repeat(12) { add(checkNotNull(engine.advance()).name) } }
        assertTrue("ролики появились", seen.any { it.endsWith(".mp4") })
    }

    @Test
    fun `исчезнувший с хранилища кадр не остаётся в очереди`() = runTest {
        val engine = library()
        engine.sync()
        assertTrue(engine.upcoming().isNotEmpty())

        available["/"] = listOf("tree-root-0.json", "tree-root-empty.json")
        engine.sync()

        assertTrue(
            "ничего из удалённой папки в очереди не осталось",
            engine.upcoming().none { it.path.startsWith("/Дети") },
        )
    }

    // ── кэш и предзагрузка ──────────────────────────────────────────────────

    @Test
    fun `предзагрузка кладёт в кэш ближайшие кадры целиком`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        val outcome = engine.prefetch()

        assertEquals("подготовлено столько, на сколько смотрим вперёд", LOOKAHEAD, outcome.fetched)
        assertEquals(
            "на каждый снимок две копии — кадр и фон под него",
            LOOKAHEAD * 2,
            cachedNames().size,
        )
        assertEquals(
            "и всё это лежит на диске",
            (LOOKAHEAD * (BYTES_PER_MICRO + BYTES_PER_FULL)).toLong(),
            engine.cacheState().usedBytes,
        )
    }

    @Test
    fun `оригиналы фотографий не кэшируются никогда`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()
        engine.prefetch()

        assertTrue(
            "в кэше только уменьшенные копии: " + cachedNames(),
            cachedNames().all { it.endsWith("-s") || it.endsWith("-xxxl") },
        )
    }

    @Test
    fun `тяжёлое видео не занимает место, а отдаётся потоком`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50, includeVideo = true)
        engine.sync()

        val huge = engine.entries.single { it.item.name == "огромное.mov" }.item
        val light = engine.entries.single { it.item.name == "лёгкое.mp4" }.item

        assertTrue("шесть гигабайт мимо кэша", engine.deliver(huge) is Delivery.Streamed)
        assertTrue("двадцать мегабайт оседают на устройстве", engine.deliver(light) is Delivery.Local)
    }

    @Test
    fun `тяжёлый ролик ждёт в очереди, пока его начало не подкачано, а потом идёт потоком`() = runTest {
        switchToCacheFolder()
        val primer = FakePrimer()
        val engine = library(pageLimit = 50, includeVideo = true, primer = primer, primeBudget = 200L * 1024 * 1024)
        engine.sync()

        val shownBefore = List(4) { checkNotNull(engine.advance()).name }
        assertTrue("на экран без подкачки не идёт: $shownBefore", "огромное.mov" !in shownBefore)
        val queued = engine.upcoming().map { it.name }
        assertTrue("но ждёт в очереди: $queued", "огромное.mov" in queued)

        engine.prefetch()
        engine.awaitPriming()

        val huge = engine.entries.single { it.item.name == "огромное.mov" }.item
        assertEquals("подкачано столько, сколько влезает в буфер", 136L * 1024 * 1024, primer.primed[huge.path])
        val shownAfter = buildList { repeat(12) { engine.advance()?.let { add(it.name) } } }
        assertTrue("подкачанный вышел на экран: $shownAfter", "огромное.mov" in shownAfter)
        val delivery = engine.deliver(huge)
        assertTrue("и идёт потоком, а не с диска", delivery is Delivery.Streamed)
        assertEquals("под своим ключом в буфере", huge.path, (delivery as Delivery.Streamed).cacheKey)
        assertTrue("в общий кэш ничего не легло", cachedNames().none { it.endsWith("-orig") })
    }

    @Test
    fun `следующий ролик не подкачивается, пока подкачанный не показан`() = runTest {
        switchToCacheFolder()
        // Порог опущен: оба ролика идут потоком и оба хотят в буфер.
        policy = policy.copy(itemThresholdBytes = 1024)
        val primer = FakePrimer()
        val engine = library(pageLimit = 50, includeVideo = true, primer = primer, primeBudget = 200L * 1024 * 1024)
        engine.sync()

        engine.prefetch()
        engine.awaitPriming()
        assertEquals("подкачан один: " + primer.primed.keys, 1, primer.primed.size)
        val first = primer.primed.keys.single()

        engine.prefetch()
        engine.awaitPriming()
        assertEquals("второй ждёт, буфер занят первым", 1, primer.primed.size)

        // Первый вышел на экран — очередь подкачки свободна.
        val shown = buildList { repeat(8) { engine.advance()?.let { add(it.path) } } }
        assertTrue("подкачанный показан: $shown", first in shown)
        engine.prefetch()
        engine.awaitPriming()
        assertEquals("теперь подкачан и второй: " + primer.primed.keys, 2, primer.primed.size)
    }

    @Test
    fun `без буфера тяжёлый ролик идёт потоком сразу, ничего не дожидаясь`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50, includeVideo = true, primer = FakePrimer(), primeBudget = 0L)
        engine.sync()

        val shown = buildList { repeat(8) { engine.advance()?.let { add(it.name) } } }
        assertTrue("ролик показан без ожидания: $shown", "огромное.mov" in shown)
    }

    @Test
    fun `сорвавшаяся подкачка не держит ролик в очереди вечно`() = runTest {
        switchToCacheFolder()
        val primer = FakePrimer(fail = true)
        val engine = library(pageLimit = 50, includeVideo = true, primer = primer, primeBudget = 200L * 1024 * 1024)
        engine.sync()

        engine.prefetch()
        engine.awaitPriming()

        val shown = buildList { repeat(8) { engine.advance()?.let { add(it.name) } } }
        assertTrue("ролик всё же показан, потоком как есть: $shown", "огромное.mov" in shown)
    }

    @Test
    fun `порог кэширования берётся из настроек, а не из кода`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50, includeVideo = true)
        engine.sync()
        val light = engine.entries.single { it.item.name == "лёгкое.mp4" }.item

        assertTrue(engine.deliver(light) is Delivery.Local)

        // Опустили порог ниже размера ролика — и он сразу пошёл потоком.
        policy = policy.copy(itemThresholdBytes = 1024)
        assertTrue(engine.deliver(light) is Delivery.Streamed)
    }

    @Test
    fun `при нехватке бюджета кэш ужимается до бюджета`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        // Бюджета хватает ровно на три снимка из пяти подготовленных.
        policy = policy.copy(budgetBytes = 3L * (BYTES_PER_MICRO + BYTES_PER_FULL))
        val outcome = engine.prefetch()

        assertTrue("что-то вытеснено", outcome.evicted > 0)
        assertTrue(
            "кэш уложился в бюджет: " + engine.cacheState().usedBytes,
            engine.cacheState().usedBytes <= policy.budgetBytes,
        )
    }

    @Test
    fun `повторная предзагрузка не качает заново то, что уже лежит`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()
        engine.prefetch()
        val afterFirst = server.requestCount

        engine.prefetch()

        assertEquals("второй заход в сеть не ходил", afterFirst, server.requestCount)
    }

    // ── обновление индекса и работа без сети ────────────────────────────────

    @Test
    fun `обход не повторяется, пока не вышел срок`() = runTest {
        val engine = library()
        engine.sync()
        val requestsAfterFirst = server.requestCount

        val skipped = engine.syncIfStale(THREE_HOURS)

        assertNull("срок не вышел — обхода не было", skipped)
        assertEquals("и в сеть не ходили", requestsAfterFirst, server.requestCount)
    }

    @Test
    fun `по истечении срока обход происходит сам`() = runTest {
        val engine = library()
        engine.sync()

        now += THREE_HOURS + 1
        val outcome = engine.syncIfStale(THREE_HOURS)

        assertNotNull("срок вышел — обход состоялся", outcome)
    }

    @Test
    fun `первый обход не откладывается ни на какой срок`() = runTest {
        val engine = library()

        val outcome = engine.syncIfStale(THREE_HOURS)

        assertNotNull("индекса ещё нет — ждать нечего", outcome)
        assertEquals(7, outcome!!.total)
    }

    @Test
    fun `удалённое с хранилища прибирается и из кэша`() = runTest {
        val engine = library()
        engine.sync()
        engine.prefetch()
        assertTrue("кэш наполнился", cachedNames().isNotEmpty())
        val before = cachedNames().size

        // Папку «Дети» удалили вместе с вложенной.
        available["/"] = listOf("tree-root-0.json", "tree-root-empty.json")
        engine.sync()

        assertTrue(
            "файлов в кэше стало меньше: было $before, стало ${cachedNames().size}",
            cachedNames().size < before,
        )
    }

    @Test
    fun `мелкий снимок после измерения выбывает из показа, а после снятия порога возвращается`() = runTest {
        switchToCacheFolder()
        var minimum = 480
        val engine = library(
            pageLimit = 50,
            minLongSide = { minimum },
            // Хранилище размеров не отдаёт: узнать их можно только по скачанной копии.
            measure = { item, _ -> if (item.name == "кадр3.jpg") 300 else 1280 },
        )
        engine.sync()
        repeat(6) {
            engine.prefetch()
            engine.advance()
        }

        val tiny = engine.entries.single { it.item.name == "кадр3.jpg" }
        assertEquals("копия измерена", 300, tiny.previewLongSidePx)
        assertEquals("и посчитана мелкой", 1, engine.indexState().tooSmall)
        val shown = (1..30).map { checkNotNull(engine.advance()).name }
        assertTrue("мелкий больше не показывается", shown.none { it == "кадр3.jpg" })

        // Размер помнится и после перезапуска — мерить заново не придётся.
        engine.flush()
        val restarted = library(pageLimit = 50, minLongSide = { 480 })
        assertEquals(300, restarted.entries.single { it.item.name == "кадр3.jpg" }.previewLongSidePx)

        // Порог опустили — снимок возвращается без переобхода.
        minimum = 0
        val relaxed = (1..30).map { checkNotNull(engine.advance()).name }
        assertTrue("после снятия порога снимок вернулся", relaxed.any { it == "кадр3.jpg" })
    }

    @Test
    fun `впервые попавшее в рамку считается свежим, даже если залито давно`() = runTest {
        switchToBulkFolder()
        // Через год после заливки свежесть по дате заливки у всех истекла, и
        // бонус свежести может взяться только из даты первого появления.
        now += 365L * 24 * 60 * 60 * 1000
        // Бонус задран, чтобы результат не зависел от удачи с зерном.
        val engine = library(pageLimit = 50, tuning = PlaylistTuning(freshnessStrength = 30f))
        engine.sync()
        assertTrue(
            "первый обход не делает свежим всё подряд",
            engine.entries.all { it.firstSeenAtMillis == null },
        )

        // Владелец отметил ещё одну подпапку — в библиотеке появились пять снимков.
        available["/Много"] = listOf("bulk-mnogo-plus.json")
        engine.sync()
        val appeared = engine.entries.filter { it.firstSeenAtMillis != null }
        assertEquals("появившиеся помечены", 5, appeared.size)
        assertTrue("временем появления", appeared.all { it.firstSeenAtMillis == now })

        // Никого ещё не показывали, так что без бонуса пятеро новых
        // шли бы наравне с двадцатью старыми.
        val picks = (1..8).map { checkNotNull(engine.advance()).path }
        val fresh = picks.count { path -> appeared.any { it.item.path == path } }
        assertEquals("все новые вышли в первой восьмёрке: $picks", 5, fresh)
    }

    @Test
    fun `погасшая ссылка на превью обновляется у хранилища, а не роняет кадр`() = runTest {
        switchToCacheFolder()
        available["/кадр3.jpg"] = listOf("refresh-kadr3.json")
        expired += "кадр3.jpg"
        val engine = library(pageLimit = 50)
        engine.sync()
        val item = engine.entries.single { it.item.name == "кадр3.jpg" }.item

        val file = engine.previewFile(item, PreviewSize.FULL)

        assertEquals("копия скачана по свежей ссылке", BYTES_PER_FULL.toLong(), file.length())
        val remembered = engine.entries.single { it.item.name == "кадр3.jpg" }.item.preview
        assertTrue("свежая ссылка легла в индекс", remembered!!.template.contains("/fresh/"))
        // И по старому элементу из очереди тоже идём по свежей ссылке.
        engine.previewFile(item, PreviewSize.MICRO)
        assertTrue("обе копии в кэше", cachedNames().size >= 2)
    }

    @Test
    fun `когда сеть легла, запасной кадр берётся из того, что уже в кэше`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()
        assertNull("пустой кэш запасного не даёт", engine.cachedFallback())

        engine.prefetch()
        offline = true

        val fallback = checkNotNull(engine.cachedFallback())
        assertTrue(
            "у запасного обе копии в кэше",
            PreviewSize.entries.all { engine.previewFile(fallback, it).isFile },
        )
    }

    @Test
    fun `снятая с отбора папка не теряет ни записей в индексе, ни копий в кэше`() = runTest {
        var chosen = FolderSelection.ALL
        // Древесные фикстуры нарезаны страницами по две записи — размер страницы обычный.
        val engine = library(selection = { chosen })
        engine.sync()
        val allPaths = engine.entries.map { it.item.path }
        assertTrue("в индексе есть и «Отпуск», и «Дети»",
            allPaths.any { it.startsWith("/Отпуск/") } && allPaths.any { it.startsWith("/Дети/") })
        // Закачать всё, что в очереди, чтобы было чему пропадать.
        repeat(4) { engine.prefetch(); engine.advance() }
        val cachedBefore = cachedNames().size
        assertTrue("кэш не пуст", cachedBefore > 0)

        // Владелец оставил только «Дети».
        chosen = FolderSelection.of(setOf("/Дети"))
        engine.applySelection()
        engine.sync()

        assertEquals("записи вне отбора остались", allPaths.toSet(), engine.entries.map { it.item.path }.toSet())
        assertEquals("копии вне отбора не стёрты", cachedBefore, cachedNames().size)
        val shown = (1..12).mapNotNull { engine.advance() }.map { it.path }
        assertTrue("а показывается только отобранное: $shown", shown.isNotEmpty() && shown.all { it.startsWith("/Дети/") })
    }

    @Test
    fun `добавленное не ждёт, пока исчерпается очередь`() = runTest {
        switchToBulkFolder()
        val engine = library(pageLimit = 50)
        engine.sync()
        val queueBefore = engine.upcoming().map { it.path }

        // В папке появились новые снимки.
        available["/Много"] = listOf("bulk-mnogo-plus.json")
        engine.sync()
        val queueAfter = engine.upcoming().map { it.path }

        assertEquals("первый кадр не выдёргивают из-под показа", queueBefore.first(), queueAfter.first())
        assertTrue("а хвост набран заново", queueBefore.drop(1) != queueAfter.drop(1))
    }

    @Test
    fun `холодный старт отдаёт кадр до того, как построен индекс`() = runTest {
        val engine = library()

        val item = engine.coldStartItem()

        assertNotNull("нашлось, что показать", item)
        assertTrue("это фотография", item!!.isShowable)
        assertTrue("и индекс при этом ещё пуст", engine.entries.isEmpty())
    }

    @Test
    fun `когда индекс есть, холодный старт в сеть не лезет`() = runTest {
        val engine = library()
        engine.sync()
        val requests = server.requestCount

        assertNull("показывать надо из индекса", engine.coldStartItem())
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun `без сети показ идёт из кэша`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()
        engine.prefetch()
        val item = checkNotNull(engine.advance())

        server.shutdown()

        val file = engine.previewFile(item, PreviewSize.FULL)
        assertTrue("кадр взят с диска", file.isFile && file.length() > 0)
        assertTrue("очередь тоже жива", engine.upcoming().isNotEmpty())
    }

    @Test
    fun `без сети обход не удаётся, но библиотека остаётся`() = runTest {
        val engine = library()
        engine.sync()
        val known = engine.entries.size

        server.shutdown()

        var failed = false
        try {
            engine.sync()
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("обход честно не удался", failed)
        assertEquals("но всё, что знали, при нас", known, engine.entries.size)
    }

    @Test
    fun `одновременные подготовки не мешают друг другу`() = runTest {
        switchToCacheFolder()
        val engine = library(pageLimit = 50)
        engine.sync()

        // Подготовку запускают и при старте, и после каждого показанного
        // кадра. Наложившись, эти заходы дрались за один временный файл, и
        // снимок стабильно не попадал в кэш.
        val outcomes = listOf(
            async { engine.prefetch() },
            async { engine.prefetch() },
            async { engine.prefetch() },
        ).map { it.await() }

        assertEquals("ни одной неудачи", 0, engine.failed.size)
        assertTrue("кэш наполнен", cachedNames().isNotEmpty())
        assertTrue(
            "и лишних недописанных файлов не осталось",
            cachedNames().none { it.endsWith(".part") },
        )
        assertTrue(outcomes.isNotEmpty())
    }

    private fun library(
        pageLimit: Int = PAGE_LIMIT,
        seed: Int = 1,
        includeVideo: Boolean = false,
        minLongSide: () -> Int = { 0 },
        measure: (MediaItem, File) -> Int? = { _, _ -> null },
        tuning: PlaylistTuning = PlaylistTuning(),
        selection: () -> FolderSelection = { FolderSelection.ALL },
        primer: StreamPrimer = StreamPrimer.NONE,
        primeBudget: Long = 0L,
        prober: DurationProber = DurationProber.NONE,
        channelBps: Long = 0L,
        maxVideoDuration: Long = 0L,
        external: () -> ExternalStore? = { null },
    ) = FrameEngine(
        source = YandexPublicDiskSource(
            publicKey = "https://disk.yandex.ru/d/TEST",
            http = OkHttpClient(),
            apiBase = server.url("/v1/disk/public/resources"),
            pageLimit = pageLimit,
            selection = selection,
        ),
        selection = selection,
        store = LibraryStore(indexFile),
        folderStore = FolderIndexStore(File(cacheDirectory, "folders.json")),
        cache = cache(),
        fetcher = MediaFetcher(OkHttpClient(), cache()),
        policy = { policy },
        clock = { now },
        random = Random(seed),
        includeVideo = { includeVideo },
        minPhotoLongSide = minLongSide,
        measure = measure,
        tuning = { tuning },
        primer = primer,
        primeBudgetBytes = { primeBudget },
        prober = prober,
        channelBps = { channelBps },
        maxVideoDurationMillis = { maxVideoDuration },
        external = external,
    )

    /** Подставной замер: длительность по размеру файла — ссылки на подставном сервере безлики. */
    private fun prober(vararg bySize: Pair<Long, Long?>) = DurationProber { _, size ->
        bySize.toMap()[size]
    }

    /** Носитель в отдельной папке: качает с того же подставного сервера. */
    private fun archive(): ArchiveStore {
        val directory = File(temporaryFolder.root, "archive").apply { mkdirs() }
        val cache = MediaCache(directory, { Long.MAX_VALUE }, { now })
        return ArchiveStore(cache, MediaFetcher(OkHttpClient(), cache))
    }

    private suspend fun shown(engine: FrameEngine, times: Int = 10): List<String> =
        buildList { repeat(times) { engine.advance()?.let { add(it.name) } } }

    @Test
    fun `ролик тяжелее канала без носителя пропускается, лёгкий идёт потоком`() = runTest {
        switchToCacheFolder()
        policy = policy.copy(itemThresholdBytes = 1024)
        // Лёгкий: 20 МБ за 10 с — 17 Мбит/с; огромный: 6 ГБ за 100 с — 480 Мбит/с.
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = prober(20_971_520L to 10_000L, 6_000_000_000L to 100_000L),
        )
        engine.sync()
        engine.awaitProbing()
        engine.prefetch()

        val names = shown(engine, 12)
        assertTrue("лёгкий показан: $names", "лёгкое.mp4" in names)
        assertTrue("огромный — нет: $names", "огромное.mov" !in names)
        assertEquals("и он числится ждущим носителя", 1, engine.waitingForStorage())
        val light = engine.entries.single { it.item.name == "лёгкое.mp4" }
        assertEquals("длительность запомнена", 10_000L, light.durationMillis)
        assertTrue(engine.deliver(light.item) is Delivery.Streamed)
    }

    @Test
    fun `пока длительность не измерена, ролик ждёт в очереди, а неразобранный считается тяжёлым`() = runTest {
        switchToCacheFolder()
        policy = policy.copy(itemThresholdBytes = 1024)
        // Замер стоит на воротах: пока они закрыты, длительности нет ни у кого.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = DurationProber { _, _ -> gate.await(); null },
        )
        engine.sync()

        val before = shown(engine, 12)
        assertTrue("без замера ролики не выходят: $before", before.none { it.endsWith(".mp4") || it.endsWith(".mov") })
        assertTrue("но один ждёт в очереди", engine.upcoming().any { it.kind == MediaKind.VIDEO })

        gate.complete(Unit)
        engine.awaitProbing()
        engine.prefetch()
        val entries = engine.entries.filter { it.item.kind == MediaKind.VIDEO }
        assertTrue("замер прошёл, заголовок не разобрался — ноль", entries.all { it.durationMillis == 0L })
        assertEquals("оба теперь ждут носителя", 2, engine.waitingForStorage())
        assertTrue("и в очереди их нет", engine.upcoming().none { it.kind == MediaKind.VIDEO })
    }

    @Test
    fun `тяжёлый ролик, чья показываемая часть помещается в подкачку, идёт потоком`() = runTest {
        switchToCacheFolder()
        // 6 ГБ за 100 с; показываем 2 с — 120 МБ, а в буфере после запаса 136 МБ.
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L, maxVideoDuration = 2_000L,
            primer = FakePrimer(), primeBudget = 200L * 1024 * 1024,
            prober = prober(6_000_000_000L to 100_000L),
        )
        engine.sync()
        val huge = engine.entries.single { it.item.name == "огромное.mov" }.item

        assertTrue(engine.deliver(huge) is Delivery.Streamed)
        assertEquals(0, engine.waitingForStorage())
    }

    @Test
    fun `пока на экране ролик, закачка на флешку не начинается`() = runTest {
        switchToCacheFolder()
        val store = archive()
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = prober(6_000_000_000L to 100_000L), external = { store },
        )
        engine.sync()

        engine.holdDownloads(true)
        engine.prefetch()
        engine.awaitArchiving()
        assertTrue("ролик на экране — на флешку ничего не качается", !store.has("/огромное.mov"))
        assertTrue("но ждёт в очереди", engine.upcoming().any { it.name == "огромное.mov" })

        engine.holdDownloads(false)
        engine.prefetch()
        engine.awaitArchiving()
        assertTrue("после ролика закачка пошла", store.has("/огромное.mov"))
    }

    @Test
    fun `тяжёлый ролик уезжает на носитель, ждёт закачки и идёт с него`() = runTest {
        switchToCacheFolder()
        val store = archive()
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = prober(6_000_000_000L to 100_000L), external = { store },
        )
        engine.sync()

        val before = shown(engine, 4)
        assertTrue("до закачки на экран не идёт: $before", "огромное.mov" !in before)
        assertTrue("но ждёт в очереди", engine.upcoming().any { it.name == "огромное.mov" })

        engine.prefetch()
        engine.awaitArchiving()
        assertTrue("лежит на носителе под своим путём", File(temporaryFolder.root, "archive/огромное.mov").isFile)
        assertTrue(store.has("/огромное.mov"))

        val after = shown(engine, 12)
        assertTrue("закачанный показан: $after", "огромное.mov" in after)
        val huge = engine.entries.single { it.item.name == "огромное.mov" }.item
        val requests = server.requestCount
        val delivery = engine.deliver(huge)
        assertTrue("идёт с носителя как локальный файл", delivery is Delivery.Local)
        assertEquals(File(temporaryFolder.root, "archive/огромное.mov"), (delivery as Delivery.Local).file)
        assertEquals("второй раз не качается", requests, server.requestCount)
        assertEquals(0, engine.waitingForStorage())
    }

    @Test
    fun `носитель пропал — тяжёлые пропускаются, вернулся — снова идут`() = runTest {
        switchToCacheFolder()
        val store = archive()
        var present = true
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = prober(6_000_000_000L to 100_000L), external = { if (present) store else null },
        )
        engine.sync()
        engine.prefetch()
        engine.awaitArchiving()

        present = false
        assertEquals(1, engine.waitingForStorage())
        assertTrue("без носителя не показывается", "огромное.mov" !in shown(engine, 12))

        present = true
        assertEquals(0, engine.waitingForStorage())
        assertTrue("с носителем — показывается, качать заново не надо", "огромное.mov" in shown(engine, 12))
    }

    @Test
    fun `удалённый на хранилище ролик убирается и с носителя`() = runTest {
        switchToCacheFolder()
        val store = archive()
        val engine = library(
            pageLimit = 50, includeVideo = true, channelBps = 50_000_000L,
            prober = prober(6_000_000_000L to 100_000L), external = { store },
        )
        engine.sync()
        engine.prefetch()
        engine.awaitArchiving()
        assertTrue(store.has("/огромное.mov"))

        available["/"] = listOf("cache-root-without-huge.json")
        engine.sync()

        assertFalse("удалили на Диске — нет и на флешке", store.has("/огромное.mov"))
    }

    /** Подставной буфер потока: помнит, сколько «подкачано», сети не трогает. */
    private class FakePrimer(private val fail: Boolean = false) : StreamPrimer {
        val primed = mutableMapOf<String, Long>()
        override fun primedBytes(key: String, limit: Long) = minOf(primed[key] ?: 0L, limit)
        override fun usedBytes() = primed.values.sum()
        override suspend fun prime(key: String, url: String, bytes: Long, onProgress: (Long) -> Unit) {
            if (fail) throw java.io.IOException("сеть легла")
            onProgress(bytes)
            primed[key] = bytes
        }
    }

    private fun cache() = MediaCache(cacheDirectory, { policy.budgetBytes }, { now })

    private fun switchToCacheFolder() {
        available.clear()
        available["/"] = listOf("cache-root-0.json")
    }

    private fun cachedNames(): List<String> =
        cacheDirectory.listFiles().orEmpty().map { it.name }.sorted()

    /** Переключает подставное хранилище на большую папку: 15 давних снимков и 5 свежих. */
    private fun switchToBulkFolder() {
        available.clear()
        available["/"] = listOf("bulk-root-0.json")
        available["/Много"] = listOf("bulk-mnogo-0.json")
    }

    /**
     * Фикстуры повторяют формат живого API, и ссылки в них ведут на настоящий
     * хост хранилища. Здесь он переписывается на подставной сервер — иначе
     * тест полез бы в сеть.
     */
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "нет фикстуры $name" }
            .bufferedReader()
            .use { it.readText() }
            .replace(REAL_DOWNLOADER, server.url("/").toString().removeSuffix("/"))

    private companion object {
        /** Маленькая страница, чтобы обход был вынужден листать. */
        const val PAGE_LIMIT = 2

        /** На сколько кадров вперёд смотрит очередь в тестах. */
        const val LOOKAHEAD = 5

        const val BYTES_PER_MICRO = 1_000
        const val BYTES_PER_FULL = 10_000
        const val BYTES_PER_ORIGINAL = 40_000

        const val THREE_HOURS = 3L * 60 * 60 * 1000

        const val REAL_DOWNLOADER = "https://downloader.disk.yandex.ru"
    }
}
