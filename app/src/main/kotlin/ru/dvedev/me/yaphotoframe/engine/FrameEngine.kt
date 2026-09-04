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
import kotlin.coroutines.coroutineContext
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
    /**
     * Текущий отбор подпапок.
     *
     * Обход по новому отбору идёт минуты, а владелец снял галочки и ждёт, что
     * снимки из снятых папок пропадут сейчас. Поэтому отбор применяется к
     * кандидатам сразу, до обхода; обход потом лишь подчистит индекс.
     */
    private val selection: () -> FolderSelection = { FolderSelection.ALL },
    /** Тяжелее скольких байт ролик не брать; ноль — без ограничения. */
    private val maxVideoBytes: () -> Long = { 0L },
    /**
     * Подкачка потока заранее.
     *
     * Тяжёлый ролик целиком на устройство не кладётся — места нет. Но канал
     * телевизора не тянет битрейт съёмки, и ролик, начатый с пустым буфером,
     * заикается с первых секунд. Поэтому его начало подкачивается заранее в
     * буфер ограниченного объёма, а на экран он выходит, когда начало на месте.
     */
    private val primer: StreamPrimer = StreamPrimer.NONE,
    /** Сколько места отдано под подкачку; ноль — не подкачивать. */
    private val primeBudgetBytes: () -> Long = { 0L },
    /** Сюда сообщается о ходе подкачки — владелец видит это в дневнике. */
    private val onPrime: (PrimeEvent) -> Unit = {},
    /** Узнаёт длительность ролика по ссылке — для битрейта. */
    private val prober: DurationProber = DurationProber.NONE,
    /** Канал до хранилища, бит/с; ноль — всё потоком, как раньше. */
    private val channelBps: () -> Long = { 0L },
    /** Сколько ролика показывается; ноль — целиком. */
    private val maxVideoDurationMillis: () -> Long = { 0L },
    /** Флешка под тяжёлые ролики; null — не выбран или не подключён. */
    private val external: () -> ExternalStore? = { null },
    private val onArchive: (ArchiveEvent) -> Unit = {},
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

    /**
     * Подкачка живёт своей жизнью: подготовка кадров идёт каждые несколько
     * секунд и не может ждать сотни мегабайт.
     */
    private val primeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val primeLock = Any()
    private var primeJob: Job? = null
    private var primeJobPath: String? = null

    @Volatile
    private var priming: PrimeState? = null

    /** Ролики, чью подкачку уже пробовали и не смогли: второй раз не ждём. */
    private val primeFailed = mutableSetOf<String>()

    /** Ролики, подкачанные за этот запуск: их не ждём, даже если буфер уже что-то вытеснил. */
    private val primedPaths = mutableSetOf<String>()

    /**
     * Подкачанный, но ещё не показанный ролик.
     *
     * Пока он стоит в очереди, следующую подкачку не начинаем: буфер один, и
     * второй ролик вытеснил бы из него начало первого — тот снова считался бы
     * неподкачанным, и карусель шла бы по кругу, а играли бы оба с пустым
     * буфером.
     */
    private var primedWaiting: String? = null

    /**
     * Пока на экране ролик, ничего тяжёлого не качается: ни подкачка, ни
     * закачка на флешку, ни лёгкие ролики в кэш. Ролик из кэша читается с
     * той же памяти телевизора, куда шла бы подкачка, и на этом декодер
     * однажды выдал кадр зелёными полосами; потоковый делит с ней сеть.
     * Снимки (сотни килобайт) качаются как обычно — очередь не должна
     * опустеть за минуту ролика.
     */
    @Volatile
    private var downloadsHeld = false

    /**
     * Придержать или отпустить закачки. При удержании идущая подкачка
     * отменяется — буфер потока помнит скачанное, и после ролика она
     * продолжится с того же места. Закачка на флешку доигрывает: её файл
     * пишется на флешку, а не в память телевизора, а отмена потеряла бы
     * скачанное.
     */
    fun holdDownloads(held: Boolean) {
        downloadsHeld = held
        if (!held) return
        synchronized(primeLock) {
            primeJob?.cancel()
            primeJob = null
            primeJobPath = null
        }
    }

    private val archiveLock = Any()
    private var archiveJob: Job? = null
    private var archiveJobPath: String? = null
    private var probeJob: Job? = null
    private val archiveFailed = mutableSetOf<String>()

    @Volatile
    private var archiving: ArchiveState? = null

    /** Останавливает подкачку — когда движок больше не нужен. */
    fun close() {
        primeScope.cancel()
    }

    val entries: List<LibraryEntry> get() = library.entries

    val syncedAtMillis: Long get() = library.syncedAtMillis

    fun showablePhotos(): List<LibraryEntry> = library.showablePhotos()

    /** Что движок готов поставить в очередь. */
    private fun candidates(): List<LibraryEntry> {
        val all = if (includeVideo()) library.showable() else library.showablePhotos()
        val minimum = minPhotoLongSide()
        val chosen = selection()
        val heaviest = maxVideoBytes()
        return all.filter { entry ->
            chosen.includes(entry.item.path) &&
                (minimum <= 0 || !entry.isSmallerThan(minimum)) &&
                (heaviest <= 0 || entry.item.kind != MediaKind.VIDEO || entry.item.sizeBytes <= heaviest) &&
                !entry.undecodable &&
                deliveryPlan(entry.item) != PlannedDelivery.Skip
        }
    }

    /** Битрейт ролика, если длительность уже измерена. */
    fun bitrateOf(path: String): Long? = entryOf(path)?.bitrateBps

    /** Декодер не берёт ролик: пометить в индексе и убрать из очереди. */
    suspend fun markUndecodable(path: String) {
        library.recordUndecodable(path)
        queueLock.withLock { queue.remove(path) }
    }

    /** Ролик стоит в очереди, но на экран пока не идёт — ждёт замера, подкачки или флешки. */
    fun waiting(item: MediaItem): Boolean = isWaiting(item)

    /** Сколько роликов ждут флешки: тяжелее канала, а класть некуда. */
    fun waitingForStorage(): Int {
        if (!includeVideo()) return 0
        val chosen = selection()
        val heaviest = maxVideoBytes()
        return library.showable().count { entry ->
            entry.item.kind == MediaKind.VIDEO &&
                chosen.includes(entry.item.path) &&
                (heaviest <= 0 || entry.item.sizeBytes <= heaviest) &&
                deliveryPlan(entry.item) == PlannedDelivery.Skip
        }
    }

    /** Отметка о неудаче снаружи — например, ролик, который не тянет сеть. */
    fun noteFailure(path: String, reason: String) {
        synchronized(failures) { failures[path] = reason }
    }

    /** Выбрасывает из очереди то, что новый отбор не включает, и набирает заново. */
    suspend fun applySelection() = withContext(Dispatchers.Default) {
        queueLock.withLock {
            val chosen = selection()
            queue.retainAll { chosen.includes(it) }
            refill()
        }
        cancelStrayPriming()
        releasePrimedIfGone()
    }

    /** Гасит подкачку ролика, которого в очереди больше нет: сотни мегабайт впустую ни к чему. */
    private suspend fun cancelStrayPriming() {
        val stray = synchronized(primeLock) {
            val path = primeJobPath ?: return
            if (primeJob?.isActive != true) return
            path
        }
        val queued = queueLock.withLock { stray in queue }
        if (queued) return
        synchronized(primeLock) {
            if (primeJobPath == stray) {
                primeJob?.cancel()
                primeJob = null
                primeJobPath = null
            }
        }
    }

    /** Подкачанный ролик из очереди выбыл, не дойдя до экрана, — очередь подкачки свободна. */
    private suspend fun releasePrimedIfGone() {
        val waiting = synchronized(primeLock) { primedWaiting } ?: return
        val queued = queueLock.withLock { waiting in queue }
        if (!queued) synchronized(primeLock) { if (primedWaiting == waiting) primedWaiting = null }
    }

    private fun entryOf(path: String): LibraryEntry? = library.entryOf(path)

    private fun isTooSmall(path: String): Boolean =
        entryOf(path)?.isSmallerThan(minPhotoLongSide()) == true

    /** Обходит хранилище, обновляет индекс и приводит очередь в соответствие. */
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
        // Копии исчезнувшего убираются уже без замка: удаление тысяч файлов
        // на флеш-памяти — секунды, и всё это время очередь была бы недоступна.
        withContext(Dispatchers.IO) { forgetVanished(vanished) }
        cancelStrayPriming()
        releasePrimedIfGone()
        startProbeSweep()
        return outcome
    }

    /**
     * Замеряет длительность всем роликам, которым она нужна, в фоне.
     *
     * Из очереди замер идёт по одному ролику за подготовку — при сотнях
     * роликов на это ушли бы часы, и всё это время они стояли бы «на замере».
     * Обход же делает по два маленьких запроса на ролик и укладывается в минуты.
     */
    private fun startProbeSweep() {
        if (channelBps() <= 0) return
        synchronized(archiveLock) {
            if (probeJob?.isActive == true) return
            probeJob = primeScope.launch { probeSweep() }
        }
    }

    private suspend fun probeSweep() {
        val threshold = policy().itemThresholdBytes
        val pending = library.showable().filter { entry ->
            entry.item.kind == MediaKind.VIDEO && entry.item.sizeBytes > threshold && entry.durationMillis == null
        }
        for (entry in pending) {
            // Могли замерить из очереди, пока обход шёл.
            if (entryOf(entry.item.path)?.durationMillis != null) continue
            try {
                probe(entry.item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сеть легла — остальным подождать следующего обхода.
                coroutineContext.ensureActive()
                return
            }
        }
    }

    /** Дожидается фонового замера — для тестов. */
    suspend fun awaitProbing() {
        synchronized(archiveLock) { probeJob }?.join()
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
        val store = external()
        vanished.forEach { path ->
            PreviewSize.entries.forEach { size -> cache.remove(previewKey(path, size)) }
            cache.remove(originalKey(path))
            store?.remove(path)
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

    /** Сколько места занято кэшем и сколько в нём файлов — это показывает диагностика. */
    fun cacheState(): CacheState = CacheState(
        usedBytes = cache.totalBytes(),
        budgetBytes = policy().budgetBytes,
        files = cache.count(),
        primedBytes = primer.usedBytes(),
        primeBudgetBytes = primeBudgetBytes(),
    )

    /** Что подкачивается прямо сейчас; null — ничего. */
    fun primeState(): PrimeState? = priming

    /** Ждёт конца текущей подкачки — для тестов. */
    suspend fun awaitPriming() {
        val job = synchronized(primeLock) { primeJob } ?: return
        job.join()
    }

    /**
     * Подтягивает ближайшие кадры и освобождает место под бюджет.
     *
     * Одно и то же правило для фотографий и для видео: что легче порога —
     * оседает в кэше целиком, что тяжелее — не качается вовсе и пойдёт потоком.
     * Уменьшенные копии фотографий всегда легче порога, поэтому библиотека
     * снимков со временем оказывается на устройстве целиком сама собой.
     *
     * Потоку — подкачка начала заранее, по одному ролику за раз, в буфер
     * заданного объёма: две параллельные делят канал пополам и обе приезжают
     * позже, чем приехали бы по очереди.
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
        var primingRequested = false
        var archiveRequested = false

        for (item in upcoming()) {
            try {
                var plan = deliveryPlan(item)
                if (plan == PlannedDelivery.Probe) {
                    probe(item)
                    plan = deliveryPlan(item)
                }
                when (plan) {
                    // Лёгкий ролик в кэш — тоже закачка, при ролике на экране подождёт.
                    PlannedDelivery.Cached -> if (downloadsHeld && item.kind != MediaKind.PHOTO) Unit else {
                        ensureCached(item)
                        fetched++
                        // Размер стал известен только сейчас: мелочь из
                        // очереди вон, пока не дошла до экрана.
                        if (isTooSmall(item.path)) queueLock.withLock { queue.remove(item.path) }
                    }

                    PlannedDelivery.Stream -> {
                        streamed++
                        // Подкачивается только первый по очереди из тех, что ждут.
                        if (isPendingStream(item) && !primingRequested) {
                            primingRequested = true
                            startPriming(item)
                        }
                    }

                    PlannedDelivery.Archive -> {
                        // На флешка — по одному: канал один, и два ролика
                        // разом приехали бы позже, чем по очереди.
                        if (isWaiting(item) && !archiveRequested && !downloadsHeld) {
                            archiveRequested = true
                            startArchiving(item)
                        }
                    }

                    // Замер не удался и сейчас — попробуем в следующий раз.
                    PlannedDelivery.Probe -> Unit

                    // Флешка пропал, пока ролик стоял в очереди.
                    PlannedDelivery.Skip -> queueLock.withLock { queue.remove(item.path) }
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
        val evicted = withContext(Dispatchers.IO) { cache.evict() + (external()?.evict() ?: 0) }
        startProbeSweep()
        return PrefetchOutcome(fetched = fetched, streamed = streamed, evicted = evicted)
    }

    /** Узнаёт длительность ролика и запоминает; сеть легла — исключение, пробуем позже. */
    private suspend fun probe(item: MediaItem) {
        val millis = prober.probe(source.downloadUrl(item), item.sizeBytes)
        library.recordDuration(item.path, millis ?: 0L)
    }

    /** Как показывать этот элемент: из кэша или потоком. */
    suspend fun deliver(item: MediaItem): Delivery = when (deliveryPlan(item)) {
        PlannedDelivery.Cached -> Delivery.Local(ensureCached(item))
        // Ключ — путь, а не ссылка: ссылки подписаны и меняются, а подкачанное
        // под старой должно пригодиться и под новой.
        PlannedDelivery.Stream -> Delivery.Streamed(source.downloadUrl(item), cacheKey = item.path)
        PlannedDelivery.Probe -> {
            probe(item)
            check(deliveryPlan(item) != PlannedDelivery.Probe) { "длительность ${item.name} не узнать" }
            deliver(item)
        }
        PlannedDelivery.Archive -> {
            val store = checkNotNull(external()) { "флешка отключена" }
            // Лежащий на флешке не требует даже ссылки: за ней ходят к API.
            if (store.has(item.path)) Delivery.Local(store.file(item.path))
            else Delivery.Local(store.fetch(item, source.downloadUrl(item)) {})
        }
        PlannedDelivery.Skip -> error("ролик ${item.name} тяжелее сети, а флешки нет")
    }

    /** Ролик стоит в очереди, но на экран пока не идёт: ждёт замера, подкачки или флешки. */
    private fun isWaiting(item: MediaItem): Boolean = when (deliveryPlan(item)) {
        PlannedDelivery.Probe -> true
        PlannedDelivery.Stream -> isPendingStream(item)
        PlannedDelivery.Archive -> external()?.has(item.path) != true
        else -> false
    }

    private fun startArchiving(item: MediaItem) {
        synchronized(archiveLock) {
            if (archiveJob?.isActive == true) {
                if (archiveJobPath == item.path) return
                archiveJob?.cancel()
            }
            archiveJobPath = item.path
            archiveJob = primeScope.launch { archive(item) }
        }
    }

    private suspend fun archive(item: MediaItem) {
        val store = external() ?: return
        val state = ArchiveState(item = item, wantedBytes = item.sizeBytes, startedAtMillis = clock())
        archiving = state
        onArchive(ArchiveEvent.Started(item))
        try {
            store.fetch(item, source.downloadUrl(item)) { state.doneBytes = it }
            onArchive(ArchiveEvent.Finished(item, clock() - state.startedAtMillis))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val reason = e.message ?: e.javaClass.simpleName
            synchronized(failures) { failures[item.path] = reason }
            synchronized(archiveLock) { archiveFailed += item.path }
            // Из очереди вон: иначе он держал бы место ожидающего до бесконечности.
            queueLock.withLock { queue.remove(item.path) }
            onArchive(ArchiveEvent.Failed(item, reason))
        } finally {
            archiving = null
        }
    }

    /** Что качается на флешка прямо сейчас; null — ничего. */
    fun archiveState(): ArchiveState? = archiving

    /** Дожидается закачки на флешка — для тестов. */
    suspend fun awaitArchiving() {
        synchronized(archiveLock) { archiveJob }?.join()
    }

    /** Сколько байт ролика стоит подкачать: всё, что влезает в буфер, но не больше самого ролика. */
    private fun primeWanted(item: MediaItem): Long {
        val budget = primeBudgetBytes() - PRIME_HEADROOM_BYTES
        if (budget <= 0) return 0L
        return minOf(item.sizeBytes, budget)
    }

    /** Ролик потоком, чьё начало ещё не подкачано: в очереди стоит, на экран не идёт. */
    private fun isPendingStream(item: MediaItem): Boolean {
        val wanted = primeWanted(item)
        if (wanted <= 0) return false
        if (synchronized(primeLock) { item.path in primeFailed || item.path in primedPaths }) return false
        return primer.primedBytes(item.path, wanted) < wanted
    }

    private fun startPriming(item: MediaItem) {
        if (downloadsHeld) return
        synchronized(primeLock) {
            // Подкачанный ждёт показа — буфер занят им, следующий подождёт.
            if (primedWaiting != null && primedWaiting != item.path) return
            if (primeJob?.isActive == true) {
                if (primeJobPath == item.path) return
                primeJob?.cancel()
            }
            primeJobPath = item.path
            primeJob = primeScope.launch { prime(item) }
        }
    }

    private suspend fun prime(item: MediaItem) {
        val wanted = primeWanted(item)
        val state = PrimeState(item = item, wantedBytes = wanted, startedAtMillis = clock())
        priming = state
        onPrime(PrimeEvent.Started(item, wanted))
        try {
            primer.prime(item.path, source.downloadUrl(item), wanted) { state.doneBytes = it }
            synchronized(primeLock) {
                primedPaths += item.path
                primedWaiting = item.path
            }
            onPrime(PrimeEvent.Finished(item, clock() - state.startedAtMillis, wanted))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Отменённая подкачка — не провал: прерванный поток бросает
            // обычную ошибку ввода-вывода, и ролик, у которого просто
            // отняли очередь, играл бы потом с пустым буфером.
            coroutineContext.ensureActive()
            val reason = e.message ?: e.javaClass.simpleName
            // Не подкачался — пусть идёт потоком как есть: ждать дальше
            // нечего, а пропускать ролик из-за буфера было бы обидно.
            synchronized(primeLock) { primeFailed += item.path }
            onPrime(PrimeEvent.Failed(item, reason))
        } finally {
            priming = null
        }
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

    /**
     * Лестница: первое подошедшее правило решает, откуда ролик пойдёт.
     *
     * Лёгкое — в кэш. Что не тяжелее канала — потоком, как и то, чья
     * показываемая часть целиком помещается в подкачку: такое не заикнётся.
     * Остальное — на флешка, а без флешки — мимо: заикающийся ролик хуже
     * пропущенного. Пока длительность не измерена, битрейт неизвестен, и
     * ролик ждёт замера.
     */
    private fun deliveryPlan(item: MediaItem): PlannedDelivery {
        if (item.kind == MediaKind.PHOTO) return PlannedDelivery.Cached
        if (item.sizeBytes <= policy().itemThresholdBytes) return PlannedDelivery.Cached
        val channel = channelBps()
        if (channel <= 0) return PlannedDelivery.Stream
        val entry = entryOf(item.path)
        val duration = entry?.durationMillis ?: return PlannedDelivery.Probe
        val bitrate = entry.bitrateBps
        if (bitrate != null) {
            if (bitrate <= channel) return PlannedDelivery.Stream
            val cap = maxVideoDurationMillis()
            val shownMillis = if (cap > 0) minOf(cap, duration) else duration
            val shownBytes = item.sizeBytes * shownMillis / duration
            if (shownBytes <= primeBudgetBytes() - PRIME_HEADROOM_BYTES) return PlannedDelivery.Stream
        }
        if (external() == null) return PlannedDelivery.Skip
        // Не доехавший до флешки (например, крупнее, чем принимает FAT32) второй
        // раз до перезапуска не качается: узнавать это на четвёртом гигабайте дорого.
        if (synchronized(archiveLock) { item.path in archiveFailed }) return PlannedDelivery.Skip
        return PlannedDelivery.Archive
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

    private enum class PlannedDelivery { Cached, Stream, Probe, Archive, Skip }

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
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            val entry = entryOf(path)
            if (entry == null || entry.isSmallerThan(minPhotoLongSide())) {
                iterator.remove()
                continue
            }
            // Флешка пропал, пока ролик стоял в очереди.
            if (deliveryPlan(entry.item) == PlannedDelivery.Skip) {
                iterator.remove()
                continue
            }
            // Ожидающий (замера, подкачки, флешки) стоит на месте, показ идёт
            // мимо него: как приедет — выйдет на экран.
            if (isWaiting(entry.item)) continue
            iterator.remove()
            library.markShown(path)
            // Подкачанный пошёл на экран — буфер свободен под следующего.
            synchronized(primeLock) { if (primedWaiting == path) primedWaiting = null }
            refill()
            return entry.item
        }
        return null
    }

    private fun refill() {
        val candidates = candidates()
        if (candidates.isEmpty()) {
            queue.clear()
            return
        }

        val queued = queue.toMutableSet()
        var pending = queue.count { path -> entryOf(path)?.let { isWaiting(it.item) } == true }
        while (queue.size < policy().prefetchCount) {
            val next = playlist.pick(candidates, queued, clock()) ?: break
            queued += next.item.path
            if (isWaiting(next.item)) {
                // В очереди держим не больше одного неподкачанного: буфер
                // один, и второй ролик вытеснил бы из него первый.
                if (pending >= 1) continue
                pending++
            }
            queue.addLast(next.item.path)
        }
    }

    /** Что не удалось подготовить и почему — это показывает диагностика. */
    private val failures = linkedMapOf<String, String>()

    val failed: Map<String, String> get() = synchronized(failures) { failures.toMap() }

    private companion object {
        /**
         * Столько буфера оставляем под сам показ: пока ролик идёт, плеер
         * дописывает в буфер продолжение, и ему нужно место, иначе вытеснится
         * ещё не сыгранное начало.
         */
        const val PRIME_HEADROOM_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Подкачка начала ролика в буфер потока.
 *
 * Снаружи, потому что буфер принадлежит плееру: он же и читает из него при
 * показе. Движок знает только, сколько уже лежит и сколько хочется.
 */
interface StreamPrimer {
    /** Сколько байт от начала ролика уже лежит подряд, не больше [limit]. */
    fun primedBytes(key: String, limit: Long): Long

    /** Сколько всего занимает буфер. */
    fun usedBytes(): Long

    /** Кладёт первые [bytes] байт; [onProgress] получает, сколько уже лежит. */
    suspend fun prime(key: String, url: String, bytes: Long, onProgress: (Long) -> Unit)

    companion object {
        /** Подкачки нет: всё идёт потоком как есть. */
        val NONE = object : StreamPrimer {
            override fun primedBytes(key: String, limit: Long) = 0L
            override fun usedBytes() = 0L
            override suspend fun prime(key: String, url: String, bytes: Long, onProgress: (Long) -> Unit) = Unit
        }
    }
}

/** Ход подкачки — это показывает диагностика. */
class PrimeState(val item: MediaItem, val wantedBytes: Long, val startedAtMillis: Long) {
    @Volatile
    var doneBytes: Long = 0L
}

/** Что случилось с подкачкой. */
sealed interface PrimeEvent {
    data class Started(val item: MediaItem, val bytes: Long) : PrimeEvent
    data class Finished(val item: MediaItem, val tookMillis: Long, val bytes: Long) : PrimeEvent
    data class Failed(val item: MediaItem, val reason: String) : PrimeEvent
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
    /** Сколько роликов телевизор не декодирует. */
    val undecodable: Int = 0,
)

/** Занятость кэша; буфер потока лежит отдельно и в бюджет не входит. */
data class CacheState(
    val usedBytes: Long,
    val budgetBytes: Long,
    val files: Int,
    val primedBytes: Long = 0L,
    val primeBudgetBytes: Long = 0L,
)

/** Что сделала подготовка: сколько положено в кэш, сколько оставлено потоку, сколько вытеснено. */
data class PrefetchOutcome(val fetched: Int, val streamed: Int, val evicted: Int)

/**
 * Флешка: тяжёлые ролики лежат на нём целиком, под своими путями.
 *
 * Ключ — путь на хранилище, так что на флешке получается то же дерево, что и
 * на Диске: её можно вынуть и показать где угодно.
 */
interface ExternalStore {
    fun has(path: String): Boolean

    /** Лежащий файл; обращение отмечается, чтобы вытеснялось давно не показанное. */
    fun file(path: String): File

    /** Пути всего, что лежит, — чтобы прибрать удалённое на хранилище. */
    fun keys(): List<String>

    fun remove(path: String): Boolean

    /** Отдаёт файл, при необходимости скачав; уже лежащий не качается снова. */
    suspend fun fetch(item: MediaItem, url: String, onProgress: (Long) -> Unit): File

    /** Освобождает место до запаса; возвращает, сколько файлов удалено. */
    fun evict(): Int
}

/** Ход закачки на флешка. */
class ArchiveState(val item: MediaItem, val wantedBytes: Long, val startedAtMillis: Long) {
    @Volatile
    var doneBytes: Long = 0L
}

sealed class ArchiveEvent {
    data class Started(val item: MediaItem) : ArchiveEvent()
    data class Finished(val item: MediaItem, val tookMillis: Long) : ArchiveEvent()
    data class Failed(val item: MediaItem, val reason: String) : ArchiveEvent()
}
