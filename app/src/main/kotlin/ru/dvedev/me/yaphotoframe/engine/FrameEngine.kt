package ru.dvedev.me.yaphotoframe.engine

import ru.dvedev.me.yaphotoframe.cache.CacheKey
import ru.dvedev.me.yaphotoframe.cache.Delivery
import ru.dvedev.me.yaphotoframe.cache.HttpFailure
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.cache.NetworkGauge
import ru.dvedev.me.yaphotoframe.cache.Storage
import ru.dvedev.me.yaphotoframe.library.LibraryEntry
import ru.dvedev.me.yaphotoframe.library.FolderIndex
import ru.dvedev.me.yaphotoframe.library.FolderIndexStore
import ru.dvedev.me.yaphotoframe.library.LibraryStore
import ru.dvedev.me.yaphotoframe.library.MediaLibrary
import ru.dvedev.me.yaphotoframe.library.SyncOutcome
import ru.dvedev.me.yaphotoframe.media.Folder
import ru.dvedev.me.yaphotoframe.media.FolderSelection
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.MediaSource
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import ru.dvedev.me.yaphotoframe.video.DurationProber
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Движок рамки: что показывать и в каком порядке.
 *
 * Единственная точка, через которую наружу видна вся механика — обход
 * Диска, индекс, память о показах и выбор следующего кадра. Отрисовка
 * снаружи: движок не знает ни про экран, ни про битмапы, поэтому проверяется
 * обычным юнит-тестом.
 *
 * Очередь строится на несколько кадров вперёд не ради красоты: чтобы
 * подгрузить кадр заранее, надо заранее знать, какой это будет кадр.
 *
 * Хранилище одно на снимки и видео (см. [Storage]); у каждого файла три
 * исхода — в хранилище, потоком, пропуск — и решает их [plan].
 *
 * @param storage где лежит хранилище сейчас: память телевизора или флешка;
 *   сервис подменяет его, когда флешку вынули или вернули.
 * @param scratch маленький кэш в памяти телевизора для того, что хранить не
 *   велено («Не хранить легче»): снимок качается туда и вытесняется первым же
 *   следующим.
 * @param decodable берёт ли декодер телевизора кодек по его строке; видео с
 *   неподходящим профилем помечается и не качается.
 * @param clock источник времени; задаётся снаружи, чтобы поведение не зависело
 *   от того, когда запустили тест.
 * @param random источник случайности; с заданным зерном порядок воспроизводим.
 */
class FrameEngine(
    private val source: MediaSource,
    store: LibraryStore,
    private val storage: () -> Storage,
    private val scratch: MediaCache,
    private val folderStore: FolderIndexStore,
    private val fetcher: MediaFetcher,
    private val prefetchCount: () -> Int = { 10 },
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
    tuning: () -> PlaylistTuning = { PlaylistTuning() },
    private val includeVideo: () -> Boolean = { false },
    private val minPhotoLongSide: () -> Int = { 0 },
    private val measure: (MediaItem, File) -> Int? = { _, _ -> null },
    private val selection: () -> FolderSelection = { FolderSelection.ALL },
    private val maxFileBytes: () -> Long = { 0L },
    private val minStorePhotoBytes: () -> Long = { 0L },
    private val minStoreVideoBytes: () -> Long = { 0L },
    private val networkBps: () -> Long = { 0L },
    private val gauge: NetworkGauge = NetworkGauge(),
    private val prober: DurationProber = DurationProber.NONE,
    private val decodable: (String) -> Boolean = { true },
    private val onDownload: (DownloadEvent) -> Unit = {},
    /** Можно ли сейчас качать видео: сервис говорит «нет», пока флешка ещё прогревается. */
    private val downloadsAllowed: () -> Boolean = { true },
) {

    private val library = MediaLibrary(source, store, clock)
    private val playlist = Playlist(random, tuning)
    private val queue = ArrayDeque<String>()

    /** Очередь правится и с потока показа, и с потока подготовки. */
    private val queueLock = Mutex()

    @Volatile
    private var folderIndex: FolderIndex = folderStore.load()

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadLock = Any()
    private var downloadJob: Job? = null
    private var downloadJobPath: String? = null
    private var probeJob: Job? = null

    /** Что качается сейчас — для страницы состояния. */
    @Volatile
    private var downloading: DownloadState? = null

    /** Не докачалось — до перезапуска второй раз не пробуем. */
    private val downloadFailed = mutableSetOf<String>()

    /** Сколько кадров готовится прямо сейчас: пока хоть один — закачка видео стоит. */
    private val preparing = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Пока на экране видео, закачки видео в хранилище стоят (по настройке
     * «Закачки во время видео»). Снимки качаются всегда: очередь не должна
     * опустеть за минуту видео.
     */
    @Volatile
    private var downloadsHeld = false

    fun holdDownloads(held: Boolean) {
        downloadsHeld = held
    }

    @Volatile
    private var closed = false

    /** Останавливает закачки — когда движок больше не нужен. */
    fun close() {
        closed = true
        workScope.cancel()
    }

    val entries: List<LibraryEntry> get() = library.entries

    val syncedAtMillis: Long get() = library.syncedAtMillis

    fun showablePhotos(): List<LibraryEntry> = library.showablePhotos()

    /** Из чего выбирать: отбор папок, порог мелкости, «Видео», порог тяжести, декодер. */
    private fun candidates(): List<LibraryEntry> {
        val all = if (includeVideo()) library.showable() else library.showablePhotos()
        val minimum = minPhotoLongSide()
        val chosen = selection()
        return all.filter { entry ->
            chosen.includes(entry.item.path) &&
                (minimum <= 0 || !entry.isSmallerThan(minimum)) &&
                !entry.undecodable &&
                plan(entry.item) != Plan.Skip
        }
    }

    /** Когда снимок показывали в последний раз; null — ещё ни разу. */
    fun lastShownAtMillis(path: String): Long? = entryOf(path)?.lastShownAtMillis

    /** Битрейт видео, если заголовок уже прочитан. */
    fun bitrateOf(path: String): Long? = entryOf(path)?.bitrateBps

    /** Декодер не берёт видео: пометить в индексе и убрать из очереди. */
    suspend fun markUndecodable(path: String) {
        library.recordUndecodable(path)
        queueLock.withLock { queue.remove(path) }
    }

    /** Файл стоит в очереди, но на экран пока не идёт: ждёт заголовка или закачки. */
    fun waiting(item: MediaItem): Boolean = isWaiting(item)

    /** Пойдёт ли видео потоком, если дойдёт до экрана. */
    fun streamed(item: MediaItem): Boolean = plan(item) == Plan.Stream

    /** Сколько видео в очереди ждут закачки. */
    suspend fun waitingCount(): Int = queueLock.withLock {
        queue.count { path -> entryOf(path)?.let { awaitsDownload(it.item) } == true }
    }

    /** Скорость сети: измеренная по закачкам, бит/с; null — замеров не было. */
    fun measuredNetworkBps(): Long? = gauge.measuredBps()

    /** Что решает судьбу потока: ручное значение или измеренное. */
    fun effectiveNetworkBps(): Long? = gauge.effectiveBps(networkBps())

    fun noteFailure(path: String, reason: String) {
        synchronized(failures) { failures[path] = reason }
    }

    /**
     * Отбор папок изменился: очередь чистится от лишнего и добирается из
     * новых кандидатов. Закачка того, что выпало из очереди, отменяется.
     */
    suspend fun applySelection() = withContext(Dispatchers.Default) {
        queueLock.withLock {
            val chosen = selection()
            queue.retainAll { chosen.includes(it) }
            refill()
        }
        cancelStrayDownload()
    }

    private suspend fun cancelStrayDownload() {
        val stray = synchronized(downloadLock) {
            val path = downloadJobPath ?: return
            if (downloadJob?.isActive != true) return
            path
        }
        val queued = queueLock.withLock { stray in queue }
        if (queued) return
        synchronized(downloadLock) {
            if (downloadJobPath == stray) {
                downloadJob?.cancel()
                downloadJob = null
                downloadJobPath = null
            }
        }
    }

    private fun entryOf(path: String): LibraryEntry? = library.entryOf(path)

    private fun isTooSmall(path: String): Boolean =
        entryOf(path)?.isSmallerThan(minPhotoLongSide()) == true

    /**
     * Полный обход Диска и слияние с индексом.
     *
     * Очередь после обхода пересобирается: то, чего больше нет, вычищается,
     * а если появилось новое — хвост очереди собирается заново, чтобы новое
     * попало в показ сразу, а не через полный круг.
     */
    suspend fun sync(): SyncOutcome {
        val before = library.entries.map { it.item.path }.toSet()
        val outcome = library.sync(inScope = selection()::includes)
        val after = library.entries.mapTo(mutableSetOf()) { it.item.path }
        val vanished = before - after

        withContext(Dispatchers.Default) {
            queueLock.withLock {
                queue.retainAll { it !in vanished }
                if (outcome.added > 0) rebuildTail()
                refill()
            }
        }
        withContext(Dispatchers.IO) { forgetVanished(vanished) }
        cancelStrayDownload()
        startProbeSweep()
        return outcome
    }

    /**
     * Заголовки видео читаются в фоне, по одному, чтобы к моменту, когда видео
     * дойдёт до очереди, его битрейт и кодек уже были известны.
     */
    private fun startProbeSweep() {
        if (prober === DurationProber.NONE) return
        synchronized(downloadLock) {
            if (probeJob?.isActive == true) return
            probeJob = workScope.launch { probeSweep() }
        }
    }

    private suspend fun probeSweep() {
        val pending = library.showable().filter { entry ->
            entry.item.kind == MediaKind.VIDEO && entry.durationMillis == null && !entry.undecodable
        }
        for (entry in pending) {
            if (entryOf(entry.item.path)?.durationMillis != null) continue
            try {
                probe(entry.item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сеть легла — дальше бессмысленно, следующий обход попробует снова.
                coroutineContext.ensureActive()
                return
            }
        }
    }

    /** Дожидается фонового чтения заголовков — для тестов. */
    suspend fun awaitProbing() {
        synchronized(downloadLock) { probeJob }?.join()
    }

    /** Переобход, если прошлый был давно; null — срок не вышел. */
    suspend fun syncIfStale(intervalMillis: Long): SyncOutcome? {
        val age = clock() - library.syncedAtMillis
        if (library.syncedAtMillis > 0 && age < intervalMillis) return null
        return sync()
    }

    /** Удалённое на Диске убирается и из хранилища. */
    private fun forgetVanished(vanished: Set<String>) {
        if (vanished.isEmpty()) return
        val store = storage()
        vanished.forEach { path ->
            store.forget(path)
            PreviewSize.entries.forEach { size -> scratch.remove(CacheKey.forPreview(path, size)) }
        }
    }

    private fun rebuildTail() {
        val head = queue.firstOrNull()
        queue.clear()
        if (head != null) queue.addLast(head)
    }

    /** Первый попавшийся снимок с Диска, пока индекса ещё нет; null — индекс уже есть. */
    suspend fun coldStartItem(): MediaItem? {
        if (candidates().isNotEmpty()) return null
        return source.firstShowable()
    }

    fun flush() = library.flush()

    /** Подпапки: из запомненного дерева или у Диска, с запоминанием. */
    suspend fun subfolders(path: String): List<Folder> {
        folderIndex.childrenOf(path)?.let { return it }

        val fetched = source.subfolders(path)
        folderIndex = folderIndex.withLevel(path, fetched, clock())
        folderStore.save(folderIndex)
        return fetched
    }

    /** Сколько подпапок у папки, если уровень уже разложен; null — не знаем. */
    fun knownSubfolderCount(path: String): Int? = folderIndex.childrenOf(path)?.size

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
            undecodable = entries.count { it.undecodable },
            shown = entries.count { it.lastShownAtMillis != null },
            syncedAtMillis = library.syncedAtMillis,
            failed = synchronized(failures) { failures.size },
            tooSmall = minPhotoLongSide().let { minimum -> entries.count { it.isSmallerThan(minimum) } },
        )
    }

    /** Хранилище глазами страницы: сколько чего лежит и что качается. */
    fun storageState(): StorageState {
        val store = storage()
        return StorageState(
            place = store.place,
            path = store.root.path,
            usedBytes = store.usedBytes(),
            budgetBytes = store.budgetBytes(),
            freeBytes = store.freeBytes(),
            totalBytes = store.totalBytes(),
            photos = store.count(Storage.Kind.PHOTO),
            videos = store.count(Storage.Kind.VIDEO),
            downloading = downloading,
            measuredNetworkBps = gauge.measuredBps(),
            networkSamples = gauge.sampleCount(),
        )
    }

    private val prefetchLock = Mutex()

    /**
     * Подготовка ближайших кадров: снимки — в хранилище, видео — на закачку
     * по одному, лишнее — вон из очереди.
     */
    suspend fun prefetch(): PrefetchOutcome = prefetchLock.withLock { doPrefetch() }

    private suspend fun doPrefetch(): PrefetchOutcome {
        // Показ перезапустили — прежний движок закрыт, и его подготовка
        // никому не нужна; иначе она продолжала бы качать и обходить диск
        // рядом с новым движком.
        if (closed) return PrefetchOutcome(0, 0, 0)
        var fetched = 0
        var streamed = 0
        var downloadRequested = false
        val handled = mutableSetOf<String>()

        // Два прохода: первый читает заголовки и выкидывает пропущенное, и
        // очередь добирается заново — иначе место выбывшего видео пустовало бы
        // до следующей подготовки.
        for (pass in 0 until 2) {
        var changed = false
        for (item in upcoming()) {
            if (closed) return PrefetchOutcome(fetched, streamed, 0)
            if (!handled.add(item.path)) continue
            try {
                var plan = plan(item)
                if (plan == Plan.Probe) {
                    probe(item)
                    plan = plan(item)
                    changed = true
                }
                when (plan) {
                    Plan.Store -> if (item.kind == MediaKind.PHOTO) {
                        ensurePhoto(item)
                        fetched++
                        // Размер стал известен только сейчас: мелочь из
                        // очереди вон, пока не дошла до экрана.
                        if (isTooSmall(item.path)) queueLock.withLock { queue.remove(item.path) }
                    } else if (!storage().has(storage().videoKey(item.path))) {
                        // Видео качаются по одному: сеть одна, и два видео разом
                        // приехали бы позже, чем по очереди.
                        if (!downloadRequested && !downloadsHeld && downloadsAllowed()) {
                            downloadRequested = true
                            startDownload(item)
                        }
                    }

                    // «Не хранить»: снимок скачается в момент показа, видео пойдёт потоком.
                    Plan.Fresh -> Unit

                    Plan.Stream -> streamed++

                    // Заголовок не прочитался и сейчас — попробуем в следующий раз.
                    Plan.Probe -> Unit

                    Plan.Skip -> {
                        queueLock.withLock { queue.remove(item.path) }
                        changed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Один недоступный файл не должен срывать подготовку остальных,
                // но и молчать о нём нельзя: владелец увидит причину в
                // диагностике и поймёт, почему кадр не появляется.
                synchronized(failures) { failures[item.path] = e.message ?: e.javaClass.simpleName }
            }
        }
        if (!changed) break
        }

        // Объём могли уменьшить, или место на диске съел кто-то другой:
        // список файлов перечитывается здесь, между подготовками, а не на показе.
        val evicted = withContext(Dispatchers.IO) {
            val store = storage()
            store.refresh()
            store.evict() + scratch.evict()
        }
        startProbeSweep()
        return PrefetchOutcome(fetched = fetched, streamed = streamed, evicted = evicted)
    }

    /** Читает заголовок: длительность и кодек — в индекс; неподходящий кодек — приговор. */
    private suspend fun probe(item: MediaItem) {
        val header = prober.probe(source.downloadUrl(item), item.sizeBytes)
        library.recordDuration(item.path, header?.durationMillis ?: 0L, header?.codec)
        val codec = header?.codec
        if (codec != null && !decodable(codec)) {
            markUndecodable(item.path)
            onDownload(DownloadEvent.Undecodable(item, codec))
        }
    }

    /** Откуда играть кадр, когда он дошёл до экрана. */
    suspend fun deliver(item: MediaItem): Delivery = when (plan(item)) {
        Plan.Store -> {
            val store = storage()
            val key = store.videoKey(item.path)
            if (store.has(key)) Delivery.Local(store.file(key))
            else Delivery.Local(download(item, store, key) {})
        }
        Plan.Fresh, Plan.Stream -> Delivery.Streamed(source.downloadUrl(item))
        Plan.Probe -> {
            probe(item)
            check(plan(item) != Plan.Probe) { "заголовок ${item.name} не прочитать" }
            deliver(item)
        }
        Plan.Skip -> error("видео ${item.name} пропущено: ${skipReason(item)}")
    }

    /** Почему видео пропускается — для дневника. */
    fun skipReason(item: MediaItem): String {
        val max = maxFileBytes()
        if (max > 0 && item.sizeBytes > max) return "тяжелее порога «Файл не тяжелее»"
        val entry = entryOf(item.path)
        if (entry?.undecodable == true) return "телевизор не декодирует"
        return "не помещается в хранилище и тяжелее сети"
    }

    private fun isWaiting(item: MediaItem): Boolean = plan(item) == Plan.Probe || awaitsDownload(item)

    /** Видео решено хранить, но оно ещё не скачано. */
    private fun awaitsDownload(item: MediaItem): Boolean =
        item.kind == MediaKind.VIDEO && plan(item) == Plan.Store && !storage().has(storage().videoKey(item.path))

    private fun startDownload(item: MediaItem) {
        synchronized(downloadLock) {
            if (downloadJob?.isActive == true) {
                if (downloadJobPath == item.path) return
                downloadJob?.cancel()
            }
            downloadJobPath = item.path
            downloadJob = workScope.launch { downloadInBackground(item) }
        }
    }

    private suspend fun downloadInBackground(item: MediaItem) {
        val store = storage()
        val state = DownloadState(item = item, wantedBytes = item.sizeBytes, startedAtMillis = clock())
        downloading = state
        onDownload(DownloadEvent.Started(item, store.place))
        try {
            download(item, store, store.videoKey(item.path)) { state.doneBytes = it }
            onDownload(DownloadEvent.Finished(item, clock() - state.startedAtMillis, store.place))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val reason = e.message ?: e.javaClass.simpleName
            synchronized(failures) { failures[item.path] = reason }
            synchronized(downloadLock) { downloadFailed += item.path }
            queueLock.withLock { queue.remove(item.path) }
            onDownload(DownloadEvent.Failed(item, reason))
        } finally {
            downloading = null
        }
    }

    /** Качает видео целиком в хранилище, освободив место, и замеряет сеть. */
    private suspend fun download(
        item: MediaItem,
        store: Storage,
        key: String,
        onProgress: (Long) -> Unit,
    ): File {
        if (!withContext(Dispatchers.IO) { store.makeRoom(item.sizeBytes) }) {
            throw java.io.IOException("в хранилище нет места под ${item.sizeBytes / 1_048_576} МБ")
        }
        val started = clock()
        val file = fetcher.ensure(store, key, source.downloadUrl(item), onProgress) { preparing.get() > 0 }
        // Замер честный только для закачки, которая не стояла за кадрами;
        // иначе он занижен, но в сторону осторожности — это допустимо.
        gauge.record(item.sizeBytes, clock() - started)
        return file
    }

    /** Что качается сейчас; null — ничего. */
    fun downloadState(): DownloadState? = downloading

    /** Дожидается закачки — для тестов. */
    suspend fun awaitDownload() {
        synchronized(downloadLock) { downloadJob }?.join()
    }

    /**
     * Уменьшенная копия нужного размера, из хранилища или из сети.
     *
     * Ссылки на копии подписаны и гаснут через несколько часов, а индекс
     * обновляется раз в три часа и живёт между запусками сутками. Погасшая
     * ссылка не повод пропускать снимок: спрашиваем у Диска свежую,
     * запоминаем её и качаем ещё раз. Снимок, который хранить не велено,
     * едет в кэш-времянку и вытесняется первым же следующим.
     */
    suspend fun previewFile(item: MediaItem, size: PreviewSize): File {
        // Ссылку берём из индекса, а не из переданного элемента: его могли
        // взять из очереди до того, как ссылку обновили.
        val current = entryOf(item.path)?.item ?: item
        val preview = requireNotNull(current.preview) { "у ${item.path} нет превью" }
        val fresh = plan(item) == Plan.Fresh
        val store = storage()
        val key = if (fresh) CacheKey.forPreview(item, size) else store.previewKey(item, size)
        suspend fun fetch(url: String): File =
            if (fresh) fetcher.ensure(scratch, key, url) else {
                withContext(Dispatchers.IO) { store.makeRoom(PREVIEW_ROOM_BYTES) }
                fetcher.ensure(store, key, url)
            }
        // Пока копия достаётся — закачка видео стоит: на флешке они делят
        // один поток FUSE, и кадр иначе ждал бы минутами.
        preparing.incrementAndGet()
        try {
            return try {
                fetch(preview.at(size))
            } catch (e: HttpFailure) {
                if (!e.isStaleLink) throw e
                val refreshed = source.refresh(current) ?: throw e
                val freshPreview = refreshed.preview ?: throw e
                library.updateItem(refreshed)
                fetch(freshPreview.at(size))
            }
        } finally {
            preparing.decrementAndGet()
        }
    }

    /** Кадр декодируется из файла — на это время закачка видео тоже стоит. */
    suspend fun <T> whilePreparing(block: suspend () -> T): T {
        preparing.incrementAndGet()
        try {
            return block()
        } finally {
            preparing.decrementAndGet()
        }
    }

    /** Снимок, который точно лежит в хранилище, — когда сети нет; null — нет такого. */
    suspend fun cachedFallback(): MediaItem? = withContext(Dispatchers.Default) {
        val store = storage()
        val ready = candidates().filter { entry ->
            entry.item.kind == MediaKind.PHOTO &&
                PreviewSize.entries.all { store.has(store.previewKey(entry.item.path, it)) }
        }
        val picked = playlist.pick(ready, emptySet(), clock()) ?: return@withContext null
        library.markShown(picked.item.path)
        picked.item
    }

    /**
     * Судьба файла: три исхода для видео и «не хранить» для лёгких.
     *
     * Порядок проверок — как в спецификации 1.4: порог тяжести, заголовок и
     * декодер, «не хранить легче», уже в хранилище, помещается ли, иначе
     * потоком по сети или пропуск.
     */
    private fun plan(item: MediaItem): Plan {
        val max = maxFileBytes()
        if (max > 0 && item.sizeBytes > max) return Plan.Skip
        if (item.kind == MediaKind.PHOTO) {
            val min = minStorePhotoBytes()
            return if (min > 0 && item.sizeBytes < min) Plan.Fresh else Plan.Store
        }
        val entry = entryOf(item.path)
        if (entry?.undecodable == true) return Plan.Skip
        if (entry?.durationMillis == null) return Plan.Probe
        if (synchronized(downloadLock) { item.path in downloadFailed }) return streamOrSkip(entry)
        val min = minStoreVideoBytes()
        if (min > 0 && item.sizeBytes < min) return streamOrSkip(entry)
        val store = storage()
        if (store.has(store.videoKey(item.path))) return Plan.Store
        if (store.fits(item.sizeBytes)) return Plan.Store
        return streamOrSkip(entry)
    }

    /** Потоком, если сеть тянет битрейт; пока сеть не измерена, считается медленной. */
    private fun streamOrSkip(entry: LibraryEntry): Plan {
        val network = effectiveNetworkBps() ?: return Plan.Skip
        val bitrate = entry.bitrateBps ?: return Plan.Skip
        return if (bitrate <= network) Plan.Stream else Plan.Skip
    }

    /** Обе копии снимка — кадр и фон под него — в хранилище; размер запоминается. */
    private suspend fun ensurePhoto(item: MediaItem) {
        previewFile(item, PreviewSize.MICRO)
        val full = previewFile(item, PreviewSize.FULL)
        if (entryOf(item.path)?.previewLongSidePx == null) {
            measure(item, full)?.let { library.recordPreviewSize(item.path, it) }
        }
    }

    private enum class Plan { Store, Fresh, Stream, Probe, Skip }

    /** Ближайшие кадры — их и предстоит подгрузить заранее. */
    suspend fun upcoming(): List<MediaItem> = withContext(Dispatchers.Default) {
        queueLock.withLock {
            refill()
            val known = library.entries.associateBy { it.item.path }
            queue.mapNotNull { known[it]?.item }
        }
    }

    /** Следующий кадр на экран; null — показывать нечего. */
    suspend fun advance(): MediaItem? = withContext(Dispatchers.Default) {
        queueLock.withLock { advanceLocked() }
    }

    private fun advanceLocked(): MediaItem? {
        refill()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            val entry = entryOf(path)
            if (entry == null || entry.isSmallerThan(minPhotoLongSide())) {
                iterator.remove()
                continue
            }
            if (plan(entry.item) == Plan.Skip) {
                iterator.remove()
                continue
            }
            // Ждёт заголовка или закачки — показ идёт мимо, к следующему.
            if (isWaiting(entry.item)) continue
            iterator.remove()
            library.markShown(path)
            refill()
            return entry.item
        }
        return null
    }

    /**
     * Добирает очередь до окна предзагрузки.
     *
     * Ждущих закачки в очереди не больше одного: качаются они по одному, и
     * пять ждущих подряд оставили бы экран без снимков. Видео, у которого
     * ещё не прочитан заголовок, в этот счёт не идёт: заголовок читается
     * за секунду, и тогда же решается, ждать ли ему закачки вообще.
     */
    private fun refill() {
        val candidates = candidates()
        if (candidates.isEmpty()) {
            queue.clear()
            return
        }

        val queued = queue.toMutableSet()
        var pending = queue.count { path -> entryOf(path)?.let { awaitsDownload(it.item) } == true }
        // Когда место ждущего занято, нескачанные видео из выбора выбывают
        // разом: иначе на библиотеке с тысячами ещё не показанных видео выбор
        // попадал бы в них снова и снова, и каждая попытка перебирала все файлы.
        var pool = candidates
        if (pending >= 1) pool = pool.filter { !awaitsDownload(it.item) }
        while (queue.size < prefetchCount()) {
            val next = playlist.pick(pool, queued, clock()) ?: break
            queued += next.item.path
            if (awaitsDownload(next.item)) {
                if (pending >= 1) {
                    pool = pool.filter { !awaitsDownload(it.item) }
                    continue
                }
                pending++
                pool = pool.filter { !awaitsDownload(it.item) }
            }
            queue.addLast(next.item.path)
        }
    }

    private val failures = linkedMapOf<String, String>()

    /** Что не удалось подготовить и почему — для диагностики. */
    val failed: Map<String, String> get() = synchronized(failures) { failures.toMap() }

    private companion object {
        /** Сколько места просить под копию снимка: пара сотен килобайт с запасом. */
        const val PREVIEW_ROOM_BYTES = 2L * 1024 * 1024
    }
}

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
    /** Сколько видео телевизор не декодирует. */
    val undecodable: Int = 0,
)

/** Хранилище для страницы состояния. */
data class StorageState(
    val place: Storage.Place,
    val path: String,
    val usedBytes: Long,
    val budgetBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val photos: Int,
    val videos: Int,
    val downloading: DownloadState?,
    val measuredNetworkBps: Long?,
    val networkSamples: Int,
)

data class PrefetchOutcome(val fetched: Int, val streamed: Int, val evicted: Int)

/** Ход закачки видео в хранилище. */
class DownloadState(val item: MediaItem, val wantedBytes: Long, val startedAtMillis: Long) {
    @Volatile
    var doneBytes: Long = 0L
}

sealed class DownloadEvent {
    data class Started(val item: MediaItem, val place: Storage.Place) : DownloadEvent()
    data class Finished(val item: MediaItem, val tookMillis: Long, val place: Storage.Place) : DownloadEvent()
    data class Failed(val item: MediaItem, val reason: String) : DownloadEvent()
    /** Заголовок прочитан, и декодер такой кодек не берёт: видео помечено и не качается. */
    data class Undecodable(val item: MediaItem, val codec: String) : DownloadEvent()
}
