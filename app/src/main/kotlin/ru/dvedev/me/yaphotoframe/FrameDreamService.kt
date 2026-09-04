package ru.dvedev.me.yaphotoframe

import android.graphics.BitmapFactory
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import ru.dvedev.me.yaphotoframe.cache.ArchiveStore
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.diag.Diary
import ru.dvedev.me.yaphotoframe.diag.ShowStats
import ru.dvedev.me.yaphotoframe.engine.FrameEngine
import ru.dvedev.me.yaphotoframe.engine.ArchiveEvent
import ru.dvedev.me.yaphotoframe.engine.PrimeEvent
import ru.dvedev.me.yaphotoframe.engine.PlaylistTuning
import ru.dvedev.me.yaphotoframe.engine.PrefetchOutcome
import ru.dvedev.me.yaphotoframe.library.FolderIndexStore
import ru.dvedev.me.yaphotoframe.library.LibraryStore
import ru.dvedev.me.yaphotoframe.library.SyncOutcome
import ru.dvedev.me.yaphotoframe.media.FolderSelection
import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.yandex.YandexPublicDiskSource
import ru.dvedev.me.yaphotoframe.settings.SettingsStore
import ru.dvedev.me.yaphotoframe.slideshow.FramePreparer
import ru.dvedev.me.yaphotoframe.slideshow.PreparedItem
import ru.dvedev.me.yaphotoframe.slideshow.PreparedPhoto
import ru.dvedev.me.yaphotoframe.slideshow.PreparedVideo
import ru.dvedev.me.yaphotoframe.slideshow.Slideshow
import ru.dvedev.me.yaphotoframe.tuner.TunerServer
import ru.dvedev.me.yaphotoframe.storage.ExternalMedia
import ru.dvedev.me.yaphotoframe.video.HttpDurationProber
import ru.dvedev.me.yaphotoframe.video.StreamHead
import ru.dvedev.me.yaphotoframe.video.ExoStreamPrimer
import ru.dvedev.me.yaphotoframe.video.StreamCache
import ru.dvedev.me.yaphotoframe.video.VideoPlayback
import ru.dvedev.me.yaphotoframe.ui.FramePlacement
import ru.dvedev.me.yaphotoframe.ui.GuideView
import ru.dvedev.me.yaphotoframe.ui.SlideshowView
import java.io.File

/**
 * Заставка-фоторамка.
 *
 * Показывает фотографии из публично расшаренной папки, сменяя их по кругу.
 * Ошибки на экран не выносятся: рамка — предмет мебели, а не приложение, и
 * разбираться, почему что-то не показалось, положено на странице настройки.
 */
class FrameDreamService : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var slideshowView: SlideshowView? = null

    /**
     * Обойма, в которой живёт экран показа.
     *
     * Нужна, чтобы подсказку можно было положить поверх кадров и снять,
     * не разрушая сам показ.
     */
    private var rootView: android.widget.FrameLayout? = null
    private var guideOverlay: android.view.View? = null
    private val guideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val store: SettingsStore by lazy { SettingsStore(this) }
    private val stats: ShowStats by lazy { ShowStats(File(filesDir, STATS_FILE)) }
    private var tuner: TunerServer? = null
    private var engine: FrameEngine? = null
    private var slideshowJob: Job? = null

    /** С какой папкой рамка работает прямо сейчас — чтобы заметить смену. */
    private var activeFolderUrl: String? = null

    /** Какие папки отобраны прямо сейчас — чтобы заметить смену выбора. */
    private var activeSelection: Set<String>? = null
    private var slideshow: Slideshow? = null

    /**
     * Что уже показали — чтобы можно было вернуться назад стрелкой.
     *
     * Держим десяток: листают назад на кадр-другой, а не отматывают вечер.
     */
    /**
     * История показов с курсором.
     *
     * Раньше это была стопка: «назад» снимал верх, а показанное ложилось
     * обратно. На дне стопки «назад» отдавал пусто, шёл новый кадр, ложился
     * сверху — и дальше два кадра чередовались при каждом нажатии. Курсор
     * ходит по списку туда и обратно, а новое добавляется только в конец.
     */
    private val history = ArrayList<ru.dvedev.me.yaphotoframe.media.MediaItem>()
    private var historyCursor = -1

    private val playback: VideoPlayback by lazy { VideoPlayback(this) }

    /** Идёт ли сейчас ролик — от этого зависит, сколько держать кадр. */
    private var showingVideo = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Заставка объявлена интерактивной ради одного: стрелки должны листать
        // кадры, а не выбрасывать из неё. Плата за это — остальные кнопки
        // приходится закрывать самим, система больше этого не делает.
        isInteractive = true
        isFullscreen = true
        isScreenBright = true

        ensureSlideshowView()
        Log.d(TAG, "заставка присоединена к окну")

        applyTunerState(store.current.tunerEnabled)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Diary.note("показ начат")
        startSlideshow()
        scope.launch {
            // Ползунок двинулся — экран меняется сразу, без пересборки и
            // перезапуска заставки. Ради этого тюнер и существует.
            store.settings.collectLatest { settings ->
                slideshowView?.applySettings(settings)
                applyTunerState(settings.tunerEnabled)
                if (activeFolderUrl != null && settings.folderUrl != activeFolderUrl) {
                    switchFolder(settings.folderUrl)
                } else if (activeSelection != null && settings.selectedFolders != activeSelection) {
                    // Отбор изменился — обойти надо заново, но кэш и историю
                    // показов сохраняем: снимки те же, просто часть их теперь
                    // не показывается.
                    activeSelection = settings.selectedFolders
                    Diary.note("отбор папок изменился: выбрано ${settings.selectedFolders.size}")
                    // Сначала очередь: снятое пропадает с экрана сразу, а не
                    // после обхода. Обход — следом, с отменой идущего.
                    scope.launch { runCatching { engine?.applySelection() } }
                    launchSync("обход после смены отбора не удался")
                }
            }
        }
    }

    override fun onDreamingStopped() {
        // Гасим только текущий показ. Сам scope переживает остановку: систему
        // никто не обязывает создавать новый экземпляр сервиса на каждый показ,
        // и отменённый навсегда Job молча оставил бы экран пустым.
        scope.coroutineContext.cancelChildren()
        // Отметки о показе копятся в памяти и ложатся на диск с задержкой —
        // перед остановкой их надо дописать, иначе потеряется история вечера.
        pauseHandler.removeCallbacks(autoResume)
        engine?.flush()
        Diary.note("показ остановлен")
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        guideHandler.removeCallbacksAndMessages(null)
        guideOverlay = null
        rootView = null
        tuner?.stop()
        tuner = null
        playback.stop()
        scope.cancel()
        slideshowView?.clear()
        // Экземпляр сервиса живёт в процессе дольше окна: удержание иерархии
        // копило бы её от показа к показу.
        slideshowView = null
        Log.d(TAG, "заставка отсоединена от окна")
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.close()
        Log.d(TAG, "сервис уничтожен")
        super.onDestroy()
    }

    /**
     * Возвращает экран показа, создавая его при необходимости.
     *
     * Нужно именно так: подсказка подменяет собой содержимое окна и обнуляет
     * ссылку на экран. Без восстановления рамка после указания папки молча
     * выбрасывала бы все подготовленные кадры, а на экране навсегда оставалась
     * бы инструкция — при том что папка уже указана и снимки загружены.
     */
    private fun ensureSlideshowView(): SlideshowView {
        slideshowView?.let { return it }
        val view = SlideshowView(this)
        view.onVideoLayerReleased = { playback.stop() }
        val root = android.widget.FrameLayout(this)
        root.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rootView = root
        slideshowView = view
        setContentView(root)
        return view
    }

    /**
     * Показывает подсказку поверх кадров на несколько секунд.
     *
     * Адрес страницы управления хранится только в голове владельца, и через
     * месяц его там не окажется. Пульт под рукой всегда — значит, вспомнить
     * адрес должно быть можно с пульта.
     */
    private fun flashGuide() {
        val root = rootView ?: return
        guideHandler.removeCallbacksAndMessages(null)
        guideOverlay?.let { root.removeView(it) }

        val guide = buildGuide()
        root.addView(
            guide,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        guideOverlay = guide
        guideHandler.postDelayed({ hideGuideOverlay() }, GUIDE_FLASH_MILLIS)
    }

    private fun hideGuideOverlay() {
        val root = rootView ?: return
        guideOverlay?.let { root.removeView(it) }
        guideOverlay = null
    }

    private fun buildGuide() = GuideView(
        context = this,
        version = BuildConfig.VERSION_NAME,
        addresses = if (store.current.tunerEnabled) {
            TunerServer(store, assets).addresses()
        } else {
            emptyList()
        },
        showingDemo = store.current.folderUrl == Defaults.PUBLIC_FOLDER_URL,
        donateUrl = Defaults.DONATE_URL,
    )

    private fun startSlideshow() {
        slideshowJob?.cancel()
        watchdogJob?.cancel()
        val job = scope.launch { runSlideshow() }
        slideshowJob = job
        // Цикл показа однажды тихо кончился, и рамка стояла на одном снимке:
        // причину — в дневник, чем бы она ни была.
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                Diary.problem("цикл показа завершился", cause)
            } else if (cause == null) {
                Diary.note("цикл показа завершился сам")
            }
        }
        watchdogJob = scope.launch { watchSlideshow(job) }
    }

    private var watchdogJob: Job? = null
    private var lastDisplayAtMillis = 0L

    /**
     * Сторож показа: кадр висит вдвое дольше положенного — значит, цикл
     * застрял или умер. Записываем, где именно, и запускаем показ заново.
     */
    private suspend fun watchSlideshow(job: Job) {
        var wallBefore = System.currentTimeMillis()
        var monoBefore = android.os.SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(WATCHDOG_TICK_MILLIS)
            // Настенные часы против монотонных: телевизор подводит время по
            // сети, и такой сдвиг однажды держал кадр минутами. Теперь
            // отсчёты на него не смотрят, но в дневнике он останется.
            val wallNow = System.currentTimeMillis()
            val monoNow = android.os.SystemClock.elapsedRealtime()
            val skew = (wallNow - wallBefore) - (monoNow - monoBefore)
            if (kotlin.math.abs(skew) > CLOCK_SKEW_NOTE_MILLIS) {
                Diary.note("системные часы сдвинулись на ${skew / 1000} с")
            }
            wallBefore = wallNow
            monoBefore = monoNow

            val shown = lastDisplayAtMillis
            if (shown == 0L || paused) continue
            val limit = holdMillis().coerceAtMost(UNLIMITED_VIDEO_MILLIS) + WATCHDOG_GRACE_MILLIS
            if (holdMillis() >= UNLIMITED_VIDEO_MILLIS) continue
            if (monoNow - shown < limit) continue
            val stage = slideshow?.stage ?: "нет цикла"
            Diary.problem(
                "показ застрял на шаге «$stage» (цикл ${if (job.isActive) "жив" else "мёртв"}), перезапускаю",
            )
            ru.dvedev.me.yaphotoframe.tuner.ThreadDump.text().lineSequence().forEach { Log.w(TAG, "stuck: $it") }
            startSlideshow()
            return
        }
    }

    /**
     * Переключает рамку на другую папку.
     *
     * Индекс и кэш относятся к прежней папке целиком, поэтому вычищаются: иначе
     * рамка мешала бы старые снимки с новыми и занимала место под то, чего
     * больше не показывает.
     */
    private fun switchFolder(url: String) {
        Diary.note("папка сменилась, начинаю заново")
        slideshowJob?.cancel()
        engine?.close()
        engine = null
        File(filesDir, LIBRARY_FILE).delete()
        File(cacheDir, CACHE_DIRECTORY).deleteRecursively()
        StreamCache.clear(this)
        // Ролики прежней папки на флешке тоже не нужны — дерево там её.
        scope.launch(Dispatchers.IO) {
            runCatching { externalStore()?.let { store -> store.keys().forEach(store::remove) } }
        }
        slideshowView?.clear()
        startSlideshow()
    }

    private val media: ExternalMedia by lazy { ExternalMedia(this) }
    private var externalCurrent: ArchiveStore? = null
    private var externalVolume: ExternalMedia.Volume? = null
    private var externalUuid: String? = null
    private var externalCheckedAt = 0L
    private var externalMissingNoted = false

    /**
     * Флешка под тяжёлые ролики, если он выбран и подключён.
     *
     * Движок спрашивает его на каждый ролик при наборе очереди, а перечисление
     * томов — обращение к системе; поэтому ответ живёт несколько секунд.
     * Флешку вынули — ответ станет null, и тяжёлые ролики пойдут мимо;
     * вернули — тот же том, те же файлы, ничего качать заново не надо.
     */
    @Synchronized
    private fun externalStore(): ArchiveStore? {
        val wanted = store.current.externalStorageUuid
        if (wanted.isBlank()) {
            externalCurrent = null
            externalVolume = null
            externalUuid = null
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (externalUuid == wanted && now - externalCheckedAt < EXTERNAL_CHECK_MILLIS) return externalCurrent
        externalCheckedAt = now
        val volume = runCatching { media.volume(wanted) }.getOrNull()
        val root = volume?.root
        if (volume == null || root == null || !volume.usable) {
            if (externalCurrent != null || !externalMissingNoted) {
                val why = volume?.problem?.let { ": $it" } ?: " не подключён"
                Diary.note("флешка ${volume?.label ?: wanted}$why — ролики тяжелее сети пропускаются")
                externalMissingNoted = true
            }
            externalCurrent = null
            externalVolume = null
            externalUuid = wanted
            return null
        }
        externalVolume = volume
        if (externalCurrent == null || externalUuid != wanted) {
            root.mkdirs()
            val cache = MediaCache(
                directory = root,
                budgetBytes = MediaCache.reserveBudget(root, { store.current.externalReserveBytes }),
            )
            cache.sweepLeftovers()
            externalCurrent = ArchiveStore(cache, MediaFetcher(Http.client, cache))
            externalUuid = wanted
            externalMissingNoted = false
            Diary.note("флешка ${volume.label}: ${root.path}")
        }
        return externalCurrent
    }

    /** Тома для страницы настройки: что можно выбрать флешкой. */
    private fun storageJson(): String {
        val volumes = runCatching { media.volumes() }.getOrDefault(emptyList())
        return "{\"chosen\":\"" + escape(store.current.externalStorageUuid) + "\",\"volumes\":" +
            volumes.joinToString(",", "[", "]") {
                "{\"uuid\":\"" + escape(it.uuid) + "\",\"label\":\"" + escape(it.label) +
                    "\",\"path\":\"" + escape(it.root?.path ?: "") + "\",\"totalBytes\":" + it.totalBytes +
                    ",\"freeBytes\":" + it.freeBytes + ",\"usable\":" + it.usable +
                    ",\"problem\":" + (it.problem?.let { p -> "\"" + escape(p) + "\"" } ?: "null") + "}"
            } + "}"
    }

    private fun externalJson(): String {
        val engine = engine
        val current = externalStore()
        val volume = externalVolume
        val chosen = store.current.externalStorageUuid
        return buildString {
            append('{')
            append("\"uuid\":\"").append(escape(chosen)).append("\",")
            append("\"present\":").append(current != null).append(',')
            append("\"label\":\"").append(escape(volume?.label ?: "")).append("\",")
            append("\"problem\":").append(volume?.problem?.let { "\"" + escape(it) + "\"" } ?: "null").append(',')
            append("\"path\":\"").append(escape(volume?.root?.path ?: "")).append("\",")
            append("\"usedBytes\":").append(current?.usedBytes() ?: 0).append(',')
            append("\"freeBytes\":").append(current?.freeBytes() ?: 0).append(',')
            append("\"totalBytes\":").append(volume?.totalBytes ?: 0).append(',')
            append("\"files\":").append(current?.files() ?: 0).append(',')
            append("\"reserveBytes\":").append(store.current.externalReserveBytes).append(',')
            append("\"waiting\":").append(engine?.waitingForStorage() ?: 0).append(',')
            append("\"archiving\":").append(
                engine?.archiveState()?.let {
                    "{\"name\":\"${escape(it.item.name)}\",\"wantedBytes\":${it.wantedBytes}," +
                        "\"doneBytes\":${it.doneBytes},\"startedAt\":${it.startedAtMillis}}"
                } ?: "null",
            )
            append('}')
        }
    }

    /** Порог мелкости в пикселях: доля из настроек от длинной стороны экрана. */
    private fun minPhotoLongSide(): Int {
        val metrics = resources.displayMetrics
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        return (store.current.minPhotoFraction * longSide).toInt()
    }

    /** Длинная сторона картинки без декодирования самой картинки. */
    private fun imageLongSide(file: File): Int? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return maxOf(options.outWidth, options.outHeight).takeIf { it > 0 }
    }

    private suspend fun runSlideshow() {
        try {
            ensureSlideshowView()
            if (store.current.folderUrl.isBlank()) {
                // Папка не указана — в сеть ходить не за чем, сразу подсказка.
                showGuide()
                return
            }
            val cache = MediaCache(
                directory = File(cacheDir, CACHE_DIRECTORY),
                budgetBytes = { store.current.cacheBudgetBytes },
            )
            // Буфер потока пересоздаётся под текущий объём: плеер и подкачка
            // делят один и тот же.
            val streamCache = withContext(Dispatchers.IO) {
                // Одна из прежних сборок складывала тяжёлые ролики целиком
                // сюда — на телевизоре это гигабайты, прибираем.
                File(cacheDir, "heavy").deleteRecursively()
                StreamCache.open(this@FrameDreamService, store.current.streamBufferBytes)
            }
            // Движок при создании читает индекс с диска — на большой библиотеке
            // это четыре мегабайта JSON и почти три секунды. Делать это на
            // главном потоке значило бы замереть на старте заставки.
            val engine = withContext(Dispatchers.IO) {
                FrameEngine(
                source = YandexPublicDiskSource(
                    publicKey = store.current.folderUrl,
                    http = Http.client,
                    selection = ::currentSelection,
                    onProgress = { files, folders ->
                        indexing = if (folders < 0) {
                            null
                        } else {
                            val started = indexing
                            Indexing(
                                files = files,
                                folders = folders,
                                startedAtMillis = started?.startedAtMillis ?: System.currentTimeMillis(),
                                // Прошлый размер индекса — единственная опора для
                                // оценки: сколько всего файлов, заранее неизвестно.
                                expectedFiles = started?.expectedFiles ?: (engine?.entries?.size ?: 0),
                            )
                        }
                    },
                ),
                store = LibraryStore(File(filesDir, LIBRARY_FILE)),
                folderStore = FolderIndexStore(File(filesDir, FOLDERS_FILE)),
                cache = cache,
                fetcher = MediaFetcher(Http.client, cache),
                primer = ExoStreamPrimer(streamCache),
                primeBudgetBytes = { store.current.streamBufferBytes },
                onPrime = ::reportPrime,
                prober = HttpDurationProber(Http.client),
                channelBps = { store.current.streamMaxBitrateBps },
                maxVideoDurationMillis = { store.current.videoMaxDurationMillis },
                external = ::externalStore,
                onArchive = ::reportArchive,
                policy = { store.current.cachePolicy() },
                includeVideo = { store.current.showVideo },
                minPhotoLongSide = ::minPhotoLongSide,
                measure = { _, file -> imageLongSide(file) },
                selection = ::currentSelection,
                maxVideoBytes = { store.current.videoMaxSizeBytes },
                tuning = {
                    PlaylistTuning(
                        freshnessWindowMillis =
                        store.current.freshnessWindowDays.toLong() * 24 * 60 * 60 * 1000,
                    )
                },
                )
            }
            // Прежний движок мог качать тяжёлый ролик — ему пора остановиться.
            this.engine?.close()
            this.engine = engine
            activeFolderUrl = store.current.folderUrl
            activeSelection = store.current.selectedFolders
            val preparer = FramePreparer(
                previewFile = engine::previewFile,
                deliver = engine::deliver,
                settings = { store.current },
                minLongSide = ::minPhotoLongSide,
                streamHead = { item, delivery ->
                    val key = delivery.cacheKey
                    val cache = StreamCache.current()
                    if (key != null && cache != null && item.sizeBytes > 0) {
                        StreamHead.copy(
                            cache, key, delivery.url,
                            minOf(item.sizeBytes, StreamHead.HEAD_BYTES),
                            File(cacheDir, "poster-head.${System.nanoTime()}.mov"),
                        )
                    } else {
                        null
                    }
                },
            )

            // Холодный старт: показать хоть что-нибудь, не дожидаясь обхода.
            // Полный обход большой папки — десятки запросов, и всё это время
            // владелец смотрел бы в чёрный экран.
            val cameUpCold = showColdStart(engine, preparer)

            if (engine.showablePhotos().isEmpty()) {
                // Через общий запуск: если владелец за время первого обхода
                // сменит отбор, этот обход отменится, а мы дождёмся следующего.
                launchSync("первый обход не удался")
                awaitSyncs()
                if (engine.showablePhotos().isEmpty()) {
                    // Показывать нечего: либо рамку только что поставили, либо
                    // ссылка перестала работать. Чёрный экран выглядел бы
                    // поломкой, поэтому объясняем, что делать.
                    showGuide()
                    return
                }
            } else {
                Diary.note("индекс поднят с диска: ${engine.entries.size} элементов")
                scope.launch {
                    runCatching {
                        engine.syncIfStale(store.current.indexRefreshIntervalMillis)?.let(::report)
                    }.onFailure { Diary.problem("обход не удался", it) }
                }
            }

            scope.launch {
                runCatching { reportPrefetch(engine.prefetch()) }
                    .onFailure { Diary.problem("подготовка не удалась", it) }
            }

            val slideshow = Slideshow(
                nextItem = ::nextItem,
                previousItem = ::stepBack,
                preparer = preparer,
                showDurationMillis = ::holdMillis,
                pairPortraits = { store.current.pairPortraits },
                onShow = ::display,
                animateFirst = cameUpCold,
                fallbackItem = engine::cachedFallback,
                onSkip = ::noteSkip,
                onStuck = ::noteStuck,
            )
            this.slideshow = slideshow
            coroutineScope { slideshow.run(this) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diary.problem("показ прерван", e)
            // Не достучались до хранилища при первом запуске — на экране должна
            // остаться подсказка, а не чернота.
            if (engine?.showablePhotos().isNullOrEmpty()) showGuide()
        }
    }

    /** Подготовка повисла: стеки всех потоков — в logcat, отметка — в дневник. */
    private fun noteStuck() {
        Diary.problem("подготовка кадра повисла дольше минуты, кадр пропущен; стеки потоков в logcat")
        ru.dvedev.me.yaphotoframe.tuner.ThreadDump.text().lineSequence().forEach { Log.w(TAG, "stuck: $it") }
    }

    private var lastSkipMessage: String? = null
    private var lastSkipAtMillis = 0L

    /**
     * Пропущенный кадр — в дневник, но без потопа.
     *
     * При лежащей сети отказы идут пачками по восемь каждые десять секунд и
     * вытеснили бы из дневника всё остальное. Одинаковые причины подряд
     * записываются не чаще раза в минуту.
     */
    private fun noteSkip(item: MediaItem, cause: Exception) {
        val message = cause.message ?: cause.javaClass.simpleName
        val now = System.currentTimeMillis()
        val sameAsBefore = message.substringBefore(" вернул") == lastSkipMessage?.substringBefore(" вернул")
        if (sameAsBefore && now - lastSkipAtMillis < SKIP_NOTE_INTERVAL_MILLIS) return
        lastSkipMessage = message
        lastSkipAtMillis = now
        Diary.problem("пропускаю ${item.name}: $message")
    }

    private fun currentSelection() = FolderSelection.of(store.current.selectedFolders)

    /**
     * Единственный идущий обход.
     *
     * Новый обход отменяет предыдущий: два обхода подряд по разным отборам
     * иначе заканчивались бы в непредсказуемом порядке, и индекс мог остаться
     * от устаревшего.
     */
    private var syncJob: kotlinx.coroutines.Job? = null

    private fun launchSync(failureMessage: String) {
        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                engine?.sync()?.let(::report)
            } catch (e: CancellationException) {
                indexing = null
                Diary.note("обход прерван: отбор изменился")
                throw e
            } catch (e: Exception) {
                indexing = null
                Diary.problem(failureMessage, e)
            }
        }
    }

    /** Ждёт, пока не закончится текущий обход — и тот, что его сменил. */
    private suspend fun awaitSyncs() {
        var job = syncJob
        while (job != null) {
            job.join()
            val next = syncJob
            job = if (next != null && next !== job && next.isActive) next else null
        }
    }

    /** Идущий обход: сколько файлов и папок пройдено. Null — обход не идёт. */
    private class Indexing(
        val files: Int,
        val folders: Int,
        val startedAtMillis: Long,
        val expectedFiles: Int,
    )

    @Volatile
    private var indexing: Indexing? = null

    @Volatile
    private var rescanningFolders = false

    /** Одной строкой: чем рамка сейчас занята. */
    private fun statusJson(): String {
        val index = engine?.indexState()
        val running = indexing
        val (phase, text) = when {
            running != null -> "indexing" to
                "Идёт обход папки: ${running.files} файлов в ${running.folders} папках"
            rescanningFolders -> "folders" to "Собираю дерево папок"
            index == null || index.total == 0 -> "empty" to "Индекс ещё не построен"
            else -> "idle" to "Работает штатно: индекс построен, ${index.total} файлов, " +
                "обход " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(index.syncedAtMillis))
        }
        return "{\"phase\":\"$phase\",\"text\":\"${escape(text)}\"}"
    }

    /** Крупная подсказка вместо чёрного экрана, когда показывать нечего. */
    private fun showGuide() {
        Diary.note("показывать нечего — вывожу подсказку")
        setContentView(buildGuide())
        slideshowView = null
        rootView = null
        guideOverlay = null
    }

    /**
     * Запоминает показанное — именно показанное, а не подготовленное.
     *
     * Раньше отметка ставилась при подготовке следующего кадра, и она же
     * затирала точку возврата: листание назад упиралось в один и тот же снимок,
     * потому что за спиной уже лежал заготовленный кадр вперёд.
     */
    private fun remember(item: ru.dvedev.me.yaphotoframe.media.MediaItem) {
        // Историю читает и страница со своего потока — под замком.
        synchronized(history) {
            // Показ из истории — назад или вперёд по ней — историю не меняет.
            if (historyCursor in history.indices && history[historyCursor].path == item.path) return
            if (history.lastOrNull()?.path == item.path) {
                historyCursor = history.lastIndex
                return
            }
            history.add(item)
            while (history.size > HISTORY_DEPTH) history.removeAt(0)
            historyCursor = history.lastIndex
        }
    }

    /** Предыдущий показанный кадр; null — возвращаться некуда. */
    private fun stepBack(): ru.dvedev.me.yaphotoframe.media.MediaItem? {
        if (historyCursor <= 0) return null
        historyCursor--
        return history[historyCursor]
    }

    /**
     * Следующий кадр: сначала вперёд по истории, если владелец листал назад,
     * и только потом — новый из очереди.
     */
    private suspend fun nextItem(): ru.dvedev.me.yaphotoframe.media.MediaItem? {
        if (historyCursor in 0 until history.lastIndex) {
            historyCursor++
            return history[historyCursor]
        }
        return engine?.advance()
    }

    /** Возвращает, удалось ли показать хоть что-то до построения индекса. */
    private suspend fun showColdStart(engine: FrameEngine, preparer: FramePreparer): Boolean = try {
        val item = engine.coldStartItem()
        if (item == null) {
            false
        } else {
            Diary.note("холодный старт: показываю ${item.name}, пока строится индекс")
            display(preparer.prepare(item), animate = false)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Diary.problem("холодный старт не удался", e)
        false
    }

    /**
     * Заставка сама решает, что делать с кнопками пульта.
     *
     * У `DreamService` нет `onKeyDown` — она получает события окна целиком,
     * через `Window.Callback`. Обрабатываем только нажатие; отпускание клавиши
     * приходит следом, и закрывать заставку дважды ни к чему.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val show = slideshow
        return when {
            show != null && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                show.page(1); true
            }

            show != null && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                show.page(-1); true
            }

            show != null && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER) -> {
                togglePause(); true
            }

            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                toggleSound(); true
            }

            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (guideOverlay == null) flashGuide() else hideGuideOverlay()
                true
            }

            // Всё остальное закрывает заставку — ровно так, как она вела бы
            // себя без нашего вмешательства.
            else -> {
                finish()
                true
            }
        }
    }

    private var paused = false

    /**
     * Пауза по «ОК»: кадр и ролик замирают, отсчёт до смены стоит, часы идут.
     * Стрелки листают и на паузе — сам кадр при этом остаётся на паузе.
     */
    private fun togglePause() = setPaused(!paused)

    private val pauseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoResume = Runnable {
        Diary.note("пауза снята по времени")
        setPaused(false)
    }

    /**
     * Звук в роликах — с пульта, кнопкой ↑.
     *
     * Настройка та же, что на странице, и запоминается: включили вечером —
     * утром ролики по-прежнему со звуком. Действует и на тот, что идёт сейчас.
     */
    private fun toggleSound() {
        val enabled = !store.current.videoSoundEnabled
        store.update { it.copy(videoSoundEnabled = enabled) }
        playback.setSoundEnabled(enabled)
        overlay(showingSound = enabled)
        slideshowView?.flashSound(enabled)
        Diary.note(if (enabled) "звук в роликах включён с пульта" else "звук в роликах выключен с пульта")
    }

    /** Значок звука виден, только пока идёт ролик и звук включён. */
    private fun overlay(showingSound: Boolean) {
        slideshowView?.setSound(showingVideo && showingSound)
    }

    private fun setPaused(value: Boolean) {
        paused = value
        slideshow?.paused = value
        slideshowView?.setPaused(value)
        if (showingVideo) playback.setPaused(value)
        pauseHandler.removeCallbacks(autoResume)
        // Пауза «на минутку» забывается, и рамка сутки висит на одном кадре;
        // через заданное время показ продолжается сам.
        val limit = store.current.pauseAutoResumeMillis
        if (value && limit > 0) pauseHandler.postDelayed(autoResume, limit)
        Log.i(TAG, if (value) "пауза" else "продолжаю")
    }

    /**
     * Сколько держать то, что сейчас на экране.
     *
     * У ролика свой срок: он идёт, пока не кончится, но не дольше отведённого.
     * Обычно кончается раньше — тогда плеер сам просит перелистнуть.
     */
    private fun holdMillis(): Long {
        if (!showingVideo) return store.current.showDurationMillis
        val limit = store.current.videoMaxDurationMillis
        return if (limit > 0) limit else UNLIMITED_VIDEO_MILLIS
    }

    private fun display(prepared: PreparedItem, animate: Boolean) {
        val view = slideshowView
        if (view == null) {
            // Окно уже закрыто, пока грузился кадр, — показывать некуда.
            prepared.discard()
            return
        }
        view.show(
            prepared = prepared,
            placement = FramePlacement.goldenFor(prepared.item.path),
            settings = store.current,
            animate = animate,
        )

        showingVideo = prepared is PreparedVideo
        if (!showingVideo) overlay(showingSound = false)
        // Хвост потокового ролика идёт по сети прямо сейчас — подкачку
        // следующего откладываем до смены кадра.
        engine?.primingHeld = prepared is PreparedVideo &&
            prepared.delivery is ru.dvedev.me.yaphotoframe.cache.Delivery.Streamed
        // Плеер здесь не останавливаем: пока слой с роликом виден, он держит на
        // поверхности последний кадр. Отпустим его, когда слой уйдёт.
        if (prepared is PreparedVideo) startPlayback(prepared)

        Log.i(TAG, "показываю ${describe(prepared)}")
        lastDisplayAtMillis = android.os.SystemClock.elapsedRealtime()
        remember(prepared.item)
        stats.record(System.currentTimeMillis())

        // Окно предзагрузки сдвинулось вместе с очередью — подтянуть хвост и
        // освободить место. Без этого подготовка случилась бы единожды при
        // запуске, и дальше каждый кадр качался бы в последний момент.
        scope.launch {
            runCatching {
                val outcome = engine?.prefetch() ?: return@runCatching
                // Молчим, когда ничего не изменилось: иначе лог заполнится
                // одинаковыми строчками на каждый кадр.
                if (outcome.fetched > 0 || outcome.evicted > 0) reportPrefetch(outcome)
            }
        }
    }

    private fun startPlayback(prepared: PreparedVideo) {
        val view = slideshowView ?: return
        val source = when (prepared.delivery) {
            is ru.dvedev.me.yaphotoframe.cache.Delivery.Local -> "из кэша"
            is ru.dvedev.me.yaphotoframe.cache.Delivery.Streamed -> "потоком"
        }
        Diary.note("ролик ${prepared.item.name}: $source, ${prepared.item.sizeBytes / 1_048_576} МБ")
        var stalls = 0
        overlay(showingSound = store.current.videoSoundEnabled)
        if (paused) {
            // Долистали до ролика на паузе — пусть и он стоит, пока не снимут.
            slideshowView?.post { playback.setPaused(true) }
        }
        playback.play(
            delivery = prepared.delivery,
            surface = view.videoSurface,
            soundEnabled = store.current.videoSoundEnabled,
            onEnded = {
                // Ролик кончился раньше отведённого срока — незачем держать
                // застывший последний кадр до истечения таймера.
                slideshow?.page(1)
            },
            onFailed = { error ->
                Diary.problem("не удалось проиграть ${prepared.item.name}", error)
                slideshow?.page(1)
            },
            onSizeKnown = { width, height ->
                slideshowView?.fitVideo(width, height, store.current.frameInset)
            },
            onPlaying = {
                slideshowView?.hideVideoPoster()
                // Длительность — в дневник: ролик с фотоаппарата на десять
                // секунд весит как фильм, и без неё кажется, что он оборвался.
                val seconds = playback.durationMillis() / 1000
                if (seconds > 0) Diary.note("ролик ${prepared.item.name} пошёл, ${formatSeconds(seconds)}")
            },
            onStalled = {
                // Пропускать ролик из-за заиканий не стали: владелец решил,
                // что дёрганый ролик лучше пропущенного. В дневник — первые
                // несколько остановок, дальше это уже не новость.
                stalls++
                if (stalls <= STALLS_TO_NOTE) {
                    Diary.note("ролик ${prepared.item.name} встал на подкачку ($stalls)")
                }
            },
        )
    }

    private fun describe(prepared: PreparedItem): String = when (prepared) {
        is PreparedVideo -> "${prepared.item.name} (ролик)"
        is PreparedPhoto -> if (prepared.companionItem != null) {
            "${prepared.item.name} + ${prepared.companionItem?.name} (пара)"
        } else {
            "${prepared.item.name} (${prepared.frame.width}x${prepared.frame.height})"
        }
    }

    /** Держит страницу настройки поднятой или опущенной в согласии с настройкой. */
    private fun applyTunerState(enabled: Boolean) {
        if (enabled && tuner == null) {
            tuner = TunerServer(
                store = store,
                assets = assets,
                diagnostics = ::diagnostics,
                folders = ::foldersJson,
                storage = ::storageJson,
                onRescanFolders = {
                    scope.launch {
                        rescanningFolders = true
                        runCatching {
                            Diary.note("собираю список папок")
                            val found = engine?.rebuildFolderIndex() ?: 0
                            Diary.note("список папок собран: $found")
                        }.onFailure { Diary.problem("не собрал список папок", it) }
                        rescanningFolders = false
                    }
                },
                host = "dream",
                onRefresh = { launchSync("обход по требованию не удался") },
            ).also { it.start() }
        } else if (!enabled && tuner != null) {
            tuner?.stop()
            tuner = null
            Diary.note("страница настройки выключена")
        }
    }

    /**
     * Подпапки указанного пути — для страницы выбора.
     *
     * Запрос приходит с потока сервера и там же ждёт ответа: страница всё равно
     * не может показать дерево, пока его не получила, а городить ради этого
     * очередь незачем.
     */
    private fun foldersJson(query: String): String {
        val engine = engine ?: return "[]"
        val path = query.split('&')
            .firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: "/"

        return try {
            val folders = kotlinx.coroutines.runBlocking { engine.subfolders(path) }
            val items = folders.joinToString(",", "[", "]") { folder ->
                "{\"name\":\"" + escape(folder.name) + "\",\"path\":\"" + escape(folder.path) + "\"}"
            }
            "{\"folders\":" + items +
                ",\"builtAt\":" + engine.foldersBuiltAtMillis +
                ",\"known\":" + engine.foldersKnown + "}"
        } catch (e: Exception) {
            Diary.problem("не смог перечислить подпапки «$path»", e)
            "{\"folders\":[],\"builtAt\":0,\"known\":0}"
        }
    }

    /** Состояние рамки для страницы настройки — она и заменяет собой кабель. */
    private fun diagnostics(): String {
        val engine = engine
        val index = engine?.indexState()
        val cache = engine?.cacheState()
        return buildString {
            append('{')
            append("\"index\":{")
            append("\"total\":").append(index?.total ?: 0).append(',')
            append("\"photos\":").append(index?.photos ?: 0).append(',')
            append("\"videos\":").append(index?.videos ?: 0).append(',')
            append("\"unshowable\":").append(index?.unshowable ?: 0).append(',')
            append("\"shown\":").append(index?.shown ?: 0).append(',')
            append("\"failed\":").append(index?.failed ?: 0).append(',')
            append("\"tooSmall\":").append(index?.tooSmall ?: 0).append(',')
            append("\"syncedAt\":").append(index?.syncedAtMillis ?: 0).append(',')
            // Пока идёт обход, страница показывает, сколько уже пройдено:
            // на большом Диске это минуты, и без счётчика кажется, что рамка
            // повисла на одном снимке.
            append("\"indexing\":").append(
                indexing?.let {
                    "{\"files\":${it.files},\"folders\":${it.folders}," +
                        "\"startedAt\":${it.startedAtMillis},\"expectedFiles\":${it.expectedFiles}}"
                } ?: "null",
            )
            append("},")
            append("\"status\":").append(statusJson()).append(',')
            append("\"cache\":{")
            append("\"usedBytes\":").append(cache?.usedBytes ?: 0).append(',')
            append("\"budgetBytes\":").append(cache?.budgetBytes ?: 0).append(',')
            append("\"files\":").append(cache?.files ?: 0).append(',')
            append("\"primedBytes\":").append(cache?.primedBytes ?: 0).append(',')
            append("\"primeBudgetBytes\":").append(cache?.primeBudgetBytes ?: 0).append(',')
            // Что подкачивается: сотни мегабайт едут десятки секунд, и без
            // счётчика непонятно, почему ролик всё не показывается.
            append("\"priming\":").append(
                engine?.primeState()?.let {
                    "{\"name\":\"${escape(it.item.name)}\",\"wantedBytes\":${it.wantedBytes}," +
                        "\"doneBytes\":${it.doneBytes},\"startedAt\":${it.startedAtMillis}}"
                } ?: "null",
            )
            append("},")
            append("\"external\":").append(externalJson()).append(',')
            append("\"queue\":").append(
                jsonItems(engine?.let { kotlinx.coroutines.runBlocking { it.upcoming() } }.orEmpty()),
            )
            append(',')
            // Что уже показано — с путями: понравившийся снимок ищут потом на
            // Диске, а с экрана имя файла не прочесть.
            append("\"recent\":").append(jsonItems(synchronized(history) { history.reversed() }))
            append(',')
            append("\"hourly\":").append(stats.byHour().joinToString(",", "[", "]"))
            append(',')
            append("\"shows\":").append(stats.total()).append(',')
            append("\"log\":").append(jsonArray(Diary.recent().reversed().map(Diary::format)))
            append(',')
            append("\"failures\":").append(
                jsonArray(engine?.failed.orEmpty().map { (path, reason) -> "$path — $reason" }),
            )
            append(',')
            append("\"errors\":").append(jsonArray(Diary.errors().reversed().map(Diary::format)))
            append('}')
        }
    }

    private fun jsonItems(items: List<ru.dvedev.me.yaphotoframe.media.MediaItem>): String =
        items.joinToString(",", "[", "]") {
            val bitrate = engine?.bitrateOf(it.path)
            "{\"name\":\"" + escape(it.name) + "\",\"path\":\"" + escape(it.path) + "\"" +
                (if (bitrate != null) ",\"bitrate\":$bitrate" else "") + "}"
        }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { "\"" + escape(it) + "\"" }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    private fun reportPrefetch(outcome: PrefetchOutcome) {
        val state = engine?.cacheState() ?: return
        Diary.note(
            "подготовка: положено ${outcome.fetched}, потоком ${outcome.streamed}, " +
                "вытеснено ${outcome.evicted}; кэш ${state.usedBytes / 1024 / 1024} МБ " +
                "в ${state.files} файлах из ${state.budgetBytes / 1024 / 1024} МБ",
        )
    }

    /** Подкачка — в дневник: по ней видно и канал, и почему ролик ждёт. */
    private fun reportPrime(event: PrimeEvent) {
        when (event) {
            is PrimeEvent.Started ->
                Diary.note(
                    "ролик ${event.item.name}: подкачиваю заранее ${event.bytes / 1_048_576} МБ " +
                        "из ${event.item.sizeBytes / 1_048_576}",
                )
            is PrimeEvent.Finished -> {
                val seconds = maxOf(1L, event.tookMillis / 1000)
                val speed = event.bytes / 1_048_576.0 / seconds
                Diary.note(
                    "ролик ${event.item.name} подкачан за ${formatSeconds(seconds)}, " +
                        "${"%.1f".format(speed)} МБ/с",
                )
            }
            is PrimeEvent.Failed ->
                Diary.problem("ролик ${event.item.name} не подкачался, пойдёт потоком как есть: ${event.reason}")
        }
    }

    /** Закачка на флешка — в дневник: по ней видно и канал, и почему ролик ждёт. */
    private fun reportArchive(event: ArchiveEvent) {
        when (event) {
            is ArchiveEvent.Started ->
                Diary.note("ролик ${event.item.name}: качаю на флешку ${event.item.sizeBytes / 1_048_576} МБ")
            is ArchiveEvent.Finished -> {
                val seconds = maxOf(1L, event.tookMillis / 1000)
                val speed = event.item.sizeBytes / 1_048_576.0 / seconds
                Diary.note(
                    "ролик ${event.item.name} на флешке за ${formatSeconds(seconds)}, " +
                        "${"%.1f".format(speed)} МБ/с",
                )
            }
            is ArchiveEvent.Failed -> {
                val tooLarge = event.reason.contains("too large", ignoreCase = true) ||
                    event.reason.contains("EFBIG")
                val why = if (tooLarge) {
                    "файл больше, чем принимает флешка (FAT32 берёт до 4 ГБ — нужна exFAT или NTFS)"
                } else {
                    event.reason
                }
                Diary.problem("ролик ${event.item.name} не доехал до флешки: $why")
            }
        }
    }

    private fun formatSeconds(seconds: Long): String =
        if (seconds < 60) "$seconds с" else "${seconds / 60} мин ${seconds % 60} с"

    private fun report(outcome: SyncOutcome) {
        Diary.note(
            "обход папки: всего ${outcome.total} (фото ${outcome.photos}, видео ${outcome.videos}, " +
                "без превью ${outcome.unshowable}), добавилось ${outcome.added}, " +
                "исчезло ${outcome.removed}",
        )
    }

    private companion object {
        const val TAG = "YaPhotoFrame"
        const val LIBRARY_FILE = "library.json"
        const val FOLDERS_FILE = "folders.json"
        const val STATS_FILE = "show-stats.csv"
        const val CACHE_DIRECTORY = "media"
        const val WATCHDOG_TICK_MILLIS = 20_000L
        const val CLOCK_SKEW_NOTE_MILLIS = 3_000L
        const val WATCHDOG_GRACE_MILLIS = 90_000L
        const val EXTERNAL_CHECK_MILLIS = 3_000L
        const val HISTORY_DEPTH = 10
        const val SKIP_NOTE_INTERVAL_MILLIS = 60_000L

        /** Столько остановок на подкачку — и ролик пропускается. */
        const val STALLS_TO_NOTE = 3
        /** Сколько держать подсказку, вызванную с пульта. */
        const val GUIDE_FLASH_MILLIS = 10_000L
        /** «Без ограничения» для ролика: сутки, которых не бывает. */
        const val UNLIMITED_VIDEO_MILLIS = 24L * 60 * 60 * 1000
    }
}
