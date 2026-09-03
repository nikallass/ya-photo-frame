package ru.dvedev.me.yaphotoframe.engine

import ru.dvedev.me.yaphotoframe.cache.CacheKey
import ru.dvedev.me.yaphotoframe.cache.CachePolicy
import ru.dvedev.me.yaphotoframe.cache.Delivery
import ru.dvedev.me.yaphotoframe.cache.HttpFailure
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.library.LibraryEntry
import ru.dvedev.me.yaphotoframe.library.FolderIndex
import ru.dvedev.me.yaphotoframe.library.FolderIndexStore
import ru.dvedev.me.yaphotoframe.library.LibraryStore
import ru.dvedev.me.yaphotoframe.library.MediaLibrary
import ru.dvedev.me.yaphotoframe.library.SyncOutcome
import ru.dvedev.me.yaphotoframe.media.Folder
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.MediaSource
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Движок рамки: что показывать и в каком порядке.
 *
 * Единственная точка, через которую наружу видна вся механика — обход
 * хранилища, индекс, память о показах и выбор следующего кадра. Отрисовка
 * снаружи: движок не знает ни про экран, ни про битмапы, поэтому проверяется
 * обычным юнит-тестом.
 *
 * Очередь строится на несколько кадров вперёд не ради красоты: чтобы
 * подгрузить кадр заранее, надо заранее знать, какой это будет кадр.
 *
 * @param clock источник времени; задаётся снаружи, чтобы поведение не зависело
 *   от того, когда запустили тест.
 * @param random источник случайности; с заданным зерном порядок воспроизводим.
 */
class FrameEngine(
    private val source: MediaSource,
    store: LibraryStore,
    private val cache: MediaCache,
    private val folderStore: FolderIndexStore,
    private val fetcher: MediaFetcher,
    private val policy: () -> CachePolicy = { CachePolicy() },
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
    tuning: () -> PlaylistTuning = { PlaylistTuning() },
    /**
     * Брать ли видео в очередь показа.
     *
     * Индексируются они в любом случае, поэтому включение не требует
     * переобхода хранилища.
     */
    private val includeVideo: () -> Boolean = { false },
    /**
     * Мельче скольких пикселей по длинной стороне снимок не показывать.
     *
     * Превью, кропы и картинки из мессенджеров на большом экране выглядят
     * почтовой маркой. Ноль — показывать всё.
     */
    private val minPhotoLongSide: () -> Int = { 0 },
    /**
     * Длинная сторона скачанной копии в пикселях, или null, если не разобрать.
     *
     * Хранилище размеров не отдаёт, так что измерять можно только скачанное.
     * Снаружи, потому что движок про картинки ничего не знает.
     */
    private val measure: (MediaItem, File) -> Int? = { _, _ -> null },
) {

    private val library = MediaLibrary(source, store, clock)
    private val playlist = Playlist(random, tuning)
    private val queue = ArrayDeque<String>()

    /**
     * Очередь трогают из разных мест — показ, подготовка, обход, диагностика.
     * Раньше всё это жило на главном потоке и было сериализовано само собой;
     * выбор кадра на шести тысячах записей заставлял главный поток замирать
     * ровно в начале растворения. Теперь он идёт в фоне, и очередь под замком.
     */
    private val queueLock = Mutex()

    @Volatile
    private var folderIndex: FolderIndex = folderStore.load()

    val entries: List<LibraryEntry> get() = library.entries

    val syncedAtMillis: Long get() = library.syncedAtMillis

    fun showablePhotos(): List<LibraryEntry> = library.showablePhotos()

    /** Что движок готов поставить в очередь. */
    private fun candidates(): List<LibraryEntry> {
        val all = if (includeVideo()) library.showable() else library.showablePhotos()
        val minimum = minPhotoLongSide()
        return if (minimum <= 0) all else all.filter { !it.isSmallerThan(minimum) }
    }

    private fun entryOf(path: String): LibraryEntry? =
        library.entries.firstOrNull { it.item.path == path }

    private fun isTooSmall(path: String): Boolean =
        entryOf(path)?.isSmallerThan(minPhotoLongSide()) == true

    /** Обходит хранилище, обновляет индекс и приводит очередь в соответствие. */
    suspend fun sync(): SyncOutcome {
        val before = library.entries.map { it.item.path }.toSet()
        val outcome = library.sync()
        val after = library.entries.mapTo(mutableSetOf()) { it.item.path }

        withContext(Dispatchers.Default) {
            queueLock.withLock {
                forgetVanished(before - after)
                if (outcome.added > 0) rebuildTail()
                refill()
            }
        }
        return outcome
    }

    /**
     * Обходит хранилище, если с прошлого раза прошло достаточно времени.
     *
     * Обход десятка подпапок — это десятки запросов; делать его при каждом
     * включении заставки расточительно, а владелец пополняет папку вручную и
     * понемногу, так что мгновенная реакция и не нужна. Возвращает null, если
     * обход не потребовался.
     */
    suspend fun syncIfStale(intervalMillis: Long): SyncOutcome? {
        val age = clock() - library.syncedAtMillis
        if (library.syncedAtMillis > 0 && age < intervalMillis) return null
        return sync()
    }

    /**
     * Выбрасывает исчезнувшее отовсюду: из очереди и из кэша.
     *
     * Удалили на хранилище — значит видеть это больше не хотят, и держать копию
     * на устройстве не только бессмысленно, но и неприятно.
     */
    private fun forgetVanished(vanished: Set<String>) {
        if (vanished.isEmpty()) return
        queue.retainAll { it !in vanished }
        vanished.forEach { path ->
            PreviewSize.entries.forEach { size -> cache.remove(previewKey(path, size)) }
            cache.remove(originalKey(path))
        }
    }

    /**
     * Оставляет в очереди только тот кадр, что уже готовится, и набирает хвост
     * заново.
     *
     * Иначе добавленные снимки ждали бы, пока исчерпается очередь, — при минуте
     * показа это десять минут после того, как владелец положил фотографии в
     * папку и пошёл смотреть.
     */
    private fun rebuildTail() {
        val head = queue.firstOrNull()
        queue.clear()
        if (head != null) queue.addLast(head)
    }

    /**
     * Первый кадр для холодного старта — до того, как построен индекс.
     *
     * Возвращает null, если индекс уже есть: тогда показывать надо из него, а не
     * лезть в сеть за случайной находкой.
     */
    suspend fun coldStartItem(): MediaItem? {
        if (candidates().isNotEmpty()) return null
        return source.firstShowable()
    }

    /** Дописывает отложенное на диск — перед остановкой показа. */
    fun flush() = library.flush()

    /**
     * Подпапки — для страницы выбора того, что показывать.
     *
     * Разложенный уровень запоминается: второй раз он открывается мгновенно и
     * без сети. Полный обход всего Диска ради этого не нужен — на большой
     * директории это сотни запросов по секунде каждый.
     */
    suspend fun subfolders(path: String): List<Folder> {
        folderIndex.childrenOf(path)?.let { return it }

        val fetched = source.subfolders(path)
        folderIndex = folderIndex.withLevel(path, fetched, clock())
        folderStore.save(folderIndex)
        return fetched
    }

    /** Когда список папок собран; ноль — ни разу. */
    val foldersBuiltAtMillis: Long get() = folderIndex.builtAtMillis

    val foldersKnown: Int get() = folderIndex.folders.size

    /**
     * Пересобирает список папок целиком.
     *
     * Отдельно от обхода файлов: список папок нужен, чтобы выбрать, что
     * показывать, а обход файлов подчиняется уже сделанному выбору.
     */
    suspend fun rebuildFolderIndex(): Int {
        val folders = source.allFolders()
        folderIndex = FolderIndex(
            builtAtMillis = clock(),
            folders = folders,
            // Полный обход выяснил подпапки у всех, до кого дошёл, и у корня.
            scanned = folders.mapTo(mutableSetOf("/")) { it.path },
        )
        folderStore.save(folderIndex)
        return folders.size
    }

    /** Сколько всего известно о папке — это показывает диагностика. */
    fun indexState(): IndexState {
        val entries = library.entries
        return IndexState(
            total = entries.size,
            photos = entries.count { it.item.kind == MediaKind.PHOTO },
            videos = entries.count { it.item.kind == MediaKind.VIDEO },
            unshowable = entries.count { !it.item.isShowable },
            shown = entries.count { it.lastShownAtMillis != null },
            syncedAtMillis = library.syncedAtMillis,
            failed = synchronized(failures) { failures.size },
            tooSmall = minPhotoLongSide().let { minimum -> entries.count { it.isSmallerThan(minimum) } },
        )
    }

    /** Сколько места занято кэшем и сколько в нём файлов — это показывает диагностика. */
    fun cacheState(): CacheState = CacheState(
        usedBytes = cache.totalBytes(),
        budgetBytes = policy().budgetBytes,
        files = cache.count(),
    )

    /**
     * Подтягивает ближайшие кадры и освобождает место под бюджет.
     *
     * Одно и то же правило для фотографий и для видео: что легче порога —
     * оседает в кэше целиком, что тяжелее — не качается вовсе и пойдёт потоком.
     * Уменьшенные копии фотографий всегда легче порога, поэтому библиотека
     * снимков со временем оказывается на устройстве целиком сама собой.
     *
     * Оригиналы фотографий не скачиваются никогда: показывается уменьшенная
     * копия, а оригинал в четырнадцать мегабайт не нужен ни для чего.
     */
    /**
     * Подготовка идёт по одной за раз.
     *
     * Её запускают и при старте, и после каждого показанного кадра, и эти
     * заходы накладывались друг на друга: одну и ту же копию качали дважды, а
     * временные файлы дрались за одно имя.
     */
    private val prefetchLock = Mutex()

    suspend fun prefetch(): PrefetchOutcome = prefetchLock.withLock { doPrefetch() }

    private suspend fun doPrefetch(): PrefetchOutcome {
        var fetched = 0
        var streamed = 0

        for (item in upcoming()) {
            try {
                when (deliveryPlan(item)) {
                    PlannedDelivery.Cached -> {
                        ensureCached(item)
                        fetched++
                        // Размер стал известен только сейчас: мелочь из
                        // очереди вон, пока не дошла до экрана.
                        if (isTooSmall(item.path)) queueLock.withLock { queue.remove(item.path) }
                    }

                    PlannedDelivery.Stream -> streamed++
                }
            } catch (e: Exception) {
                // Один недоступный файл не должен срывать подготовку остальных,
                // но и молчать о нём нельзя: владелец увидит причину в
                // диагностике и поймёт, почему снимок не появляется.
                synchronized(failures) { failures[item.path] = e.message ?: e.javaClass.simpleName }
            }
        }

        // Перебор директории кэша — сотни stat-вызовов; главному потоку тут
        // делать нечего, он в это время рисует переход.
        val evicted = withContext(Dispatchers.IO) { cache.evict() }
        return PrefetchOutcome(fetched = fetched, streamed = streamed, evicted = evicted)
    }

    /** Как показывать этот элемент: из кэша или потоком. */
    suspend fun deliver(item: MediaItem): Delivery = when (deliveryPlan(item)) {
        PlannedDelivery.Cached -> Delivery.Local(ensureCached(item))
        PlannedDelivery.Stream -> Delivery.Streamed(source.downloadUrl(item))
    }

    /**
     * Уменьшенная копия нужного размера, из кэша или из сети.
     *
     * Ссылки на копии подписаны и гаснут через несколько часов, а индекс
     * обновляется раз в три часа и живёт между запусками сутками. Погасшая
     * ссылка не повод пропускать снимок: спрашиваем у хранилища свежую,
     * запоминаем её и качаем ещё раз. Именно на этом рамка однажды встала на
     * ночь: все кадры в очереди отдали 410, и показывать стало нечего.
     */
    suspend fun previewFile(item: MediaItem, size: PreviewSize): File {
        // Ссылку берём из индекса, а не из переданного элемента: его могли
        // взять из очереди до того, как ссылку обновили.
        val current = entryOf(item.path)?.item ?: item
        val preview = requireNotNull(current.preview) { "у ${item.path} нет превью" }
        val key = CacheKey.forPreview(item, size)
        return try {
            fetcher.ensure(key, preview.at(size))
        } catch (e: HttpFailure) {
            if (!e.isStaleLink) throw e
            val fresh = source.refresh(current) ?: throw e
            val freshPreview = fresh.preview ?: throw e
            library.updateItem(fresh)
            fetcher.ensure(key, freshPreview.at(size))
        }
    }

    /**
     * Кадр, для которого ничего качать не нужно, — на случай, когда сеть легла.
     *
     * Очередь набирается без оглядки на кэш, и при пропавшей сети она вся
     * упирается в незакачанное. Библиотека же по большей части давно лежит
     * на устройстве; лучше показать из неё, чем чёрный экран.
     */
    suspend fun cachedFallback(): MediaItem? = withContext(Dispatchers.Default) {
        val ready = candidates().filter { entry ->
            entry.item.kind == MediaKind.PHOTO &&
                PreviewSize.entries.all { cache.has(previewKey(entry.item.path, it)) }
        }
        // Очередь не исключается: закачано как раз то, что в ней стояло, а
        // не открылось из неё то, что закачать не успели.
        val picked = playlist.pick(ready, emptySet(), clock()) ?: return@withContext null
        library.markShown(picked.item.path)
        picked.item
    }

    private fun previewKey(path: String, size: PreviewSize) = CacheKey.forPreview(path, size)

    private fun originalKey(path: String) = CacheKey.forOriginal(path)

    private fun deliveryPlan(item: MediaItem): PlannedDelivery = when {
        item.kind == MediaKind.PHOTO -> PlannedDelivery.Cached
        item.sizeBytes <= policy().itemThresholdBytes -> PlannedDelivery.Cached
        else -> PlannedDelivery.Stream
    }

    private suspend fun ensureCached(item: MediaItem): File =
        if (item.kind == MediaKind.PHOTO) {
            // Фон берётся из микро-копии, поэтому нужны обе.
            previewFile(item, PreviewSize.MICRO)
            val full = previewFile(item, PreviewSize.FULL)
            if (entryOf(item.path)?.previewLongSidePx == null) {
                measure(item, full)?.let { library.recordPreviewSize(item.path, it) }
            }
            full
        } else {
            fetcher.ensure(CacheKey.forOriginal(item), source.downloadUrl(item))
        }

    private enum class PlannedDelivery { Cached, Stream }

    /** Ближайшие кадры — их и предстоит подгрузить заранее. */
    suspend fun upcoming(): List<MediaItem> = withContext(Dispatchers.Default) {
        queueLock.withLock {
            refill()
            val known = library.entries.associateBy { it.item.path }
            queue.mapNotNull { known[it]?.item }
        }
    }

    /**
     * Отдаёт следующий кадр и отмечает его показанным.
     *
     * Отметка ставится здесь, а не после того, как кадр окажется на экране:
     * иначе неудачная загрузка оставила бы элемент вечно «не показанным», и
     * порядок раз за разом упирался бы в один и тот же битый файл.
     */
    suspend fun advance(): MediaItem? = withContext(Dispatchers.Default) {
        queueLock.withLock { advanceLocked() }
    }

    private fun advanceLocked(): MediaItem? {
        refill()
        val path = queue.removeFirstOrNull() ?: return null
        val entry = entryOf(path) ?: return advanceLocked()
        if (entry.isSmallerThan(minPhotoLongSide())) return advanceLocked()
        library.markShown(path)
        refill()
        return entry.item
    }

    private fun refill() {
        val candidates = candidates()
        if (candidates.isEmpty()) {
            queue.clear()
            return
        }

        val queued = queue.toMutableSet()
        while (queue.size < policy().prefetchCount) {
            val next = playlist.pick(candidates, queued, clock()) ?: break
            queue.addLast(next.item.path)
            queued += next.item.path
        }
    }

    /** Что не удалось подготовить и почему — это показывает диагностика. */
    private val failures = linkedMapOf<String, String>()

    val failed: Map<String, String> get() = synchronized(failures) { failures.toMap() }
}

/** Что известно о папке. */
data class IndexState(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val unshowable: Int,
    val shown: Int,
    val syncedAtMillis: Long,
    val failed: Int,
    /** Сколько снимков измерено и оказалось мельче порога. */
    val tooSmall: Int = 0,
)

/** Занятость кэша. */
data class CacheState(val usedBytes: Long, val budgetBytes: Long, val files: Int)

/** Что сделала подготовка: сколько положено в кэш, сколько оставлено потоку, сколько вытеснено. */
data class PrefetchOutcome(val fetched: Int, val streamed: Int, val evicted: Int)
