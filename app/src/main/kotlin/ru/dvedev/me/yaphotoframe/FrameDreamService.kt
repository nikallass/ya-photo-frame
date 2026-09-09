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
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.cache.NetworkGauge
import ru.dvedev.me.yaphotoframe.cache.Storage
import ru.dvedev.me.yaphotoframe.diag.Diary
import ru.dvedev.me.yaphotoframe.diag.ShowStats
import ru.dvedev.me.yaphotoframe.engine.FrameEngine
import ru.dvedev.me.yaphotoframe.engine.DownloadEvent
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
import ru.dvedev.me.yaphotoframe.video.VideoPlayback
import ru.dvedev.me.yaphotoframe.ui.FramePlan
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

    /** Что сейчас на экране — чтобы отложенная проверка кадра не била по следующему ролику. */
    private var currentItemPath: String? = null

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
        // Новому показу — полный срок на первый кадр. Раньше отметка прошлого
        // показа оставалась, и после одного долгого старта сторож перезапускал
        // показ каждые двадцать секунд, не давая первому кадру выйти вовсе.
        lastDisplayAtMillis = android.os.SystemClock.elapsedRealtime()
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
     * Индекс и хранилище относятся к прежней папке целиком, поэтому
     * вычищаются — и в памяти телевизора, и на флешке: иначе рамка мешала бы
     * старые снимки с новыми и занимала место под то, чего больше не показывает.
     */
    private fun switchFolder(url: String) {
        Diary.note("папка сменилась, начинаю заново")
        slideshowJob?.cancel()
        engine?.close()
        engine = null
        File(filesDir, LIBRARY_FILE).delete()
        val places = listOfNotNull(tvStorage, synchronized(this) { flashStorage })
        scope.launch(Dispatchers.IO) {
            places.forEach { runCatching { it.clear() } }
            runCatching { scratch.clear() }
        }
        slideshowView?.clear()
        startSlideshow()
    }

    private val media: ExternalMedia by lazy { ExternalMedia(this) }

    /** Скорость сети по закачкам — живёт дольше движка, чтобы замер не терялся при перезапуске показа. */
    private val gauge = NetworkGauge()

    /**
     * Кэш-времянка под снимки, которые хранить не велено («Не хранить легче»):
     * снимок качается сюда к показу и вытесняется следующими.
     */
    private val scratch: MediaCache by lazy { MediaCache(File(cacheDir, SCRATCH_DIRECTORY), { SCRATCH_BYTES }) }

    /**
     * Хранилище в памяти телевизора — есть всегда: это и место по умолчанию,
     * и запасное, когда выбранную флешку вынули.
     */
    private val tvStorage: Storage by lazy {
        val root = File(cacheDir, STORAGE_DIRECTORY)
        migrateLegacyCache(root)
        Storage(root = root, place = Storage.Place.TvMemory, capacity = ::capacity)
    }
    private var flashStorage: Storage? = null
    private var flashVolume: ExternalMedia.Volume? = null
    private var flashUuid: String? = null
    private var flashCheckedAt = 0L
    private var flashMissingNoted = false

    /** Объём хранилища из настроек: бегунок или свободное место минус запас. */
    private fun capacity(): Storage.Capacity = store.current.let {
        if (it.storageByFree) Storage.Capacity.ByFree(it.storageReserveBytes) else Storage.Capacity.Fixed(it.storageBytes)
    }

    /**
     * Хранилище прямо сейчас: память телевизора или выбранная флешка.
     *
     * Движок спрашивает его на каждый файл при наборе очереди, поэтому ответ
     * мгновенный — из того, что известно; а сама проверка флешки (обращение к
     * системе и пробная запись) идёт раз в несколько секунд в фоне. Флешку
     * вынули — рамка временно живёт в памяти телевизора и не пустеет;
     * вернули — тот же том, те же файлы, ничего качать заново не надо.
     */
    @Synchronized
    private fun storage(): Storage {
        val wanted = store.current.storageVolumeUuid
        if (wanted.isBlank()) {
            flashStorage = null
            flashVolume = null
            flashUuid = null
            return tvStorage
        }
        val now = SystemClock.elapsedRealtime()
        val stale = flashUuid != wanted || now - flashCheckedAt >= FLASH_CHECK_MILLIS
        if (stale && !flashChecking) {
            flashChecking = true
            flashCheckedAt = now
            scope.launch(Dispatchers.IO) {
                try {
                    checkFlash(wanted)
                } finally {
                    synchronized(this@FrameDreamService) { flashChecking = false }
                }
            }
        }
        return if (flashUuid == wanted) flashStorage ?: tvStorage else tvStorage
    }

    @Volatile
    private var flashChecking = false

    /** Проверяет том и, если он на месте, поднимает на нём хранилище. */
    private fun checkFlash(wanted: String) {
        // Пробная запись нужна, пока хранилище на флешке не поднято; потом
        // достаточно видеть, что том смонтирован: каждая запись на флешке
        // проходит через MediaProvider и стоит телевизору заметно.
        val known = synchronized(this) { flashStorage }?.let { (it.place as Storage.Place.Flash).uuid == wanted } == true
        val volume = runCatching { media.volume(wanted, probe = !known) }.getOrNull()
        val root = volume?.root
        val usable = volume != null && root != null && volume.usable
        val current = synchronized(this) { flashStorage }
        var fresh: Storage? = null
        if (usable && (current == null || (current.place as Storage.Place.Flash).uuid != wanted)) {
            root!!.mkdirs()
            migrateLegacyFlash(root)
            // Копии снимков — не для галереи телевизора: без этой пометки
            // MediaProvider индексирует каждую из тысяч копий.
            File(root, Storage.PREVIEWS).mkdirs()
            runCatching { File(root, Storage.PREVIEWS + "/.nomedia").createNewFile() }
            fresh = Storage(root = root, place = Storage.Place.Flash(wanted, volume!!.label), capacity = ::capacity)
        }
        synchronized(this) {
            flashVolume = volume
            flashUuid = wanted
            if (!usable) {
                if (flashStorage != null || !flashMissingNoted) {
                    val why = volume?.problem?.let { ": $it" } ?: " не подключена"
                    Diary.note("флешка ${volume?.label ?: wanted}$why — хранилище пока в памяти телевизора")
                    flashMissingNoted = true
                }
                flashStorage = null
            } else if (fresh != null) {
                flashStorage = fresh
                flashMissingNoted = false
                Diary.note("хранилище на флешке ${volume!!.label}: ${root!!.path}")
            }
        }
    }

    /**
     * Копии снимков из кэша сборок до 1.4 переезжают в хранилище одним
     * переименованием папки — качать их заново незачем. Буфер потока и
     * папка тяжёлых роликов тех же сборок — вон, это гигабайты.
     */
    private fun migrateLegacyCache(root: File) {
        val previews = File(root, Storage.PREVIEWS)
        val old = File(cacheDir, LEGACY_CACHE_DIRECTORY)
        if (old.isDirectory && !previews.exists()) {
            root.mkdirs()
            if (old.renameTo(previews)) Diary.note("копии снимков перенесены из кэша в хранилище")
        }
        old.deleteRecursively()
        File(cacheDir, "stream").deleteRecursively()
        File(cacheDir, "heavy").deleteRecursively()
    }

    /** Видео, которые сборки до 1.4 клали в корень папки на флешке, переезжают в `videos/`. */
    private fun migrateLegacyFlash(root: File) {
        val videos = File(root, Storage.VIDEOS)
        val stray = root.listFiles().orEmpty().filter {
            it.name != Storage.VIDEOS && it.name != Storage.PREVIEWS && !it.name.startsWith(".")
        }
        if (stray.isEmpty()) return
        videos.mkdirs()
        val moved = stray.count { it.renameTo(File(videos, it.name)) }
        if (moved > 0) Diary.note("видео на флешке перенесены в папку videos: $moved")
    }

    /** Тома для страницы настройки: что можно выбрать местом хранилища, и память телевизора рядом. */
    private fun volumesJson(): String {
        val volumes = runCatching { media.volumes() }.getOrDefault(emptyList())
        return "{\"chosen\":\"" + escape(store.current.storageVolumeUuid) + "\"," +
            "\"tv\":{\"totalBytes\":" + cacheDir.totalSpace + ",\"freeBytes\":" + cacheDir.usableSpace +
            ",\"usedBytes\":" + (runCatching { tvStorage.usedBytes() }.getOrDefault(0L)) + "}," +
            "\"networkBps\":" + (gauge.measuredBps() ?: 0L) + ",\"networkSamples\":" + gauge.sampleCount() + "," +
            "\"volumes\":" +
            volumes.joinToString(",", "[", "]") {
                "{\"uuid\":\"" + escape(it.uuid) + "\",\"label\":\"" + escape(it.label) +
                    "\",\"path\":\"" + escape(it.root?.path ?: "") + "\",\"totalBytes\":" + it.totalBytes +
                    ",\"freeBytes\":" + it.freeBytes + ",\"usable\":" + it.usable +
                    ",\"problem\":" + (it.problem?.let { p -> "\"" + escape(p) + "\"" } ?: "null") + "}"
            } + "}"
    }

    /** Хранилище для «Состояния»: где, сколько занято, что качается. */
    private fun storageJson(): String {
        val engine = engine
        val state = engine?.storageState()
        val chosen = store.current.storageVolumeUuid
        val volume = synchronized(this) { flashVolume }
        val place = state?.place
        val fallback = chosen.isNotBlank() && place != null && place !is Storage.Place.Flash
        return buildString {
            append('{')
            append("\"place\":\"").append(if (place is Storage.Place.Flash) "flash" else "tv").append("\",")
            append("\"chosen\":\"").append(escape(chosen)).append("\",")
            append("\"label\":\"").append(escape((place as? Storage.Place.Flash)?.label ?: volume?.label ?: "")).append("\",")
            append("\"fallback\":").append(fallback).append(',')
            append("\"problem\":").append(
                if (fallback) "\"" + escape(volume?.problem ?: "не подключена") + "\"" else "null",
            ).append(',')
            append("\"path\":\"").append(escape(state?.path ?: "")).append("\",")
            append("\"usedBytes\":").append(state?.usedBytes ?: 0).append(',')
            append("\"budgetBytes\":").append(state?.budgetBytes ?: 0).append(',')
            append("\"freeBytes\":").append(state?.freeBytes ?: 0).append(',')
            append("\"totalBytes\":").append(state?.totalBytes ?: 0).append(',')
            append("\"photos\":").append(state?.photos ?: 0).append(',')
            append("\"videos\":").append(state?.videos ?: 0).append(',')
            append("\"byFree\":").append(store.current.storageByFree).append(',')
            append("\"waiting\":").append(engine?.let { kotlinx.coroutines.runBlocking { it.waitingCount() } } ?: 0).append(',')
            append("\"networkBps\":").append(state?.measuredNetworkBps ?: 0).append(',')
            append("\"networkSamples\":").append(state?.networkSamples ?: 0).append(',')
            append("\"downloading\":").append(
                state?.downloading?.let {
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
                storage = ::storage,
                scratch = scratch,
                fetcher = MediaFetcher(Http.client),
                prefetchCount = { store.current.prefetchCount },
                prober = HttpDurationProber(Http.client),
                gauge = gauge,
                decodable = playback::decodable,
                onDownload = ::reportDownload,
                includeVideo = { store.current.showVideo },
                minPhotoLongSide = ::minPhotoLongSide,
                measure = { _, file -> imageLongSide(file) },
                selection = ::currentSelection,
                maxFileBytes = { store.current.maxFileBytes },
                minStorePhotoBytes = { store.current.minStorePhotoBytes },
                minStoreVideoBytes = { store.current.minStoreVideoBytes },
                networkBps = { store.current.networkBps },
                tuning = {
                    PlaylistTuning(
                        freshnessWindowMillis =
                        store.current.freshnessWindowDays.toLong() * 24 * 60 * 60 * 1000,
                    )
                },
                )
            }
            // Прежний движок мог качать видео — ему пора остановиться.
            this.engine?.close()
            this.engine = engine
            activeFolderUrl = store.current.folderUrl
            activeSelection = store.current.selectedFolders
            val preparer = FramePreparer(
                previewFile = engine::previewFile,
                deliver = engine::deliver,
                settings = { store.current },
                minLongSide = ::minPhotoLongSide,
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
        Diary.note(if (enabled) "звук в видео включён с пульта" else "звук в видео выключен с пульта")
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
            // Ключ с временем прошлого показа: каждый раз снимок встаёт иначе.
            key = FramePlan.showKey(prepared.item.path, engine?.lastShownAtMillis(prepared.item.path)),
            settings = store.current,
            animate = animate,
        )

        showingVideo = prepared is PreparedVideo
        currentItemPath = prepared.item.path
        if (!showingVideo) overlay(showingSound = false)
        // Пока видео на экране, закачки видео стоят по настройке «Закачки во
        // время видео»; снимки качаются всегда. Подготовка после смены кадра
        // возобновит.
        engine?.holdDownloads(prepared is PreparedVideo && !store.current.downloadsDuringVideo)
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

    /** Снимок с поверхности ролика: если он зелёно-пурпурными полосами, ролик вон. */
    private fun checkFrame(prepared: PreparedVideo) {
        val view = slideshowView ?: return
        if (currentItemPath != prepared.item.path) return
        val bitmap = runCatching { view.videoSurface.getBitmap(160, 90) }.getOrNull() ?: return
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val share = ru.dvedev.me.yaphotoframe.video.FrameCorruption.garbageShare(pixels)
        if (share < ru.dvedev.me.yaphotoframe.video.FrameCorruption.THRESHOLD) return
        Diary.problem(
            "телевизор не декодирует ${prepared.item.name}: кадр полосами (${(share * 100).toInt()} % мусора) — видео больше не показывается",
        )
        playback.stop()
        scope.launch { engine?.markUndecodable(prepared.item.path) }
        slideshow?.page(1)
    }

    private fun startPlayback(prepared: PreparedVideo) {
        val view = slideshowView ?: return
        val source = when (prepared.delivery) {
            is ru.dvedev.me.yaphotoframe.cache.Delivery.Local -> "из хранилища"
            is ru.dvedev.me.yaphotoframe.cache.Delivery.Streamed -> "потоком"
        }
        Diary.note("видео ${prepared.item.name}: $source, ${prepared.item.sizeBytes / 1_048_576} МБ")
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
                // Видео кончилось раньше отведённого срока — незачем держать
                // застывший последний кадр до истечения таймера.
                slideshow?.page(1)
            },
            onFailed = { error ->
                Diary.problem("не удалось проиграть ${prepared.item.name}", error)
                slideshow?.page(1)
            },
            onUnsupported = { codec ->
                // Декодер такой профиль не берёт, а взявшись, рисует полосами:
                // ролик вон из очереди насовсем, в индексе пометка.
                Diary.problem("телевизор не декодирует ${prepared.item.name} ($codec) — видео больше не показывается")
                scope.launch { engine?.markUndecodable(prepared.item.path) }
                slideshow?.page(1)
            },
            onSizeKnown = { width, height ->
                slideshowView?.fitVideo(width, height, store.current.insetFor(width, height))
            },
            onPlaying = {
                slideshowView?.hideVideoPoster()
                // Декодер может не упасть, а рисовать мусор: через полторы и
                // четыре секунды заглядываем в кадр.
                for (delay in listOf(1_500L, 4_000L)) {
                    slideshowView?.postDelayed({ checkFrame(prepared) }, delay)
                }
                // Длительность — в дневник: ролик с фотоаппарата на десять
                // секунд весит как фильм, и без неё кажется, что он оборвался.
                val seconds = playback.durationMillis() / 1000
                if (seconds > 0) Diary.note("видео ${prepared.item.name} пошло, ${formatSeconds(seconds)}")
            },
            onStalled = {
                // Пропускать ролик из-за заиканий не стали: владелец решил,
                // что дёрганый ролик лучше пропущенного. В дневник — первые
                // несколько остановок, дальше это уже не новость.
                stalls++
                if (stalls <= STALLS_TO_NOTE) {
                    Diary.note("видео ${prepared.item.name} встало на подкачку ($stalls)")
                }
            },
        )
    }

    private fun describe(prepared: PreparedItem): String = when (prepared) {
        is PreparedVideo -> "${prepared.item.name} (видео)"
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
                storage = ::volumesJson,
                hasVolume = { uuid -> runCatching { media.volumes() }.getOrDefault(emptyList()).any { it.uuid == uuid } },
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
            ru.dvedev.me.yaphotoframe.tuner.foldersJson(
                folders, engine.foldersBuiltAtMillis, engine.foldersKnown, engine::knownSubfolderCount,
            )
        } catch (e: Exception) {
            Diary.problem("не смог перечислить подпапки «$path»", e)
            "{\"folders\":[],\"builtAt\":0,\"known\":0}"
        }
    }

    /** Состояние рамки для страницы настройки — она и заменяет собой кабель. */
    private fun diagnostics(): String {
        val engine = engine
        val index = engine?.indexState()
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
            append("\"undecodable\":").append(index?.undecodable ?: 0).append(',')
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
            append("\"storage\":").append(storageJson()).append(',')
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
            val waiting = engine?.waiting(it) == true
            val streamed = !waiting && engine?.streamed(it) == true
            "{\"name\":\"" + escape(it.name) + "\",\"path\":\"" + escape(it.path) + "\"" +
                (if (bitrate != null) ",\"bitrate\":$bitrate" else "") +
                (if (waiting) ",\"waiting\":true" else "") +
                (if (streamed) ",\"streamed\":true" else "") + "}"
        }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { "\"" + escape(it) + "\"" }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    private fun reportPrefetch(outcome: PrefetchOutcome) {
        val state = engine?.storageState() ?: return
        Diary.note(
            "подготовка: положено ${outcome.fetched}, потоком ${outcome.streamed}, " +
                "вытеснено ${outcome.evicted}; хранилище ${state.usedBytes / 1024 / 1024} МБ " +
                "из ${state.budgetBytes / 1024 / 1024} МБ (снимков ${state.photos}, видео ${state.videos})",
        )
    }

    /** Закачка видео — в дневник: по ней видно и скорость сети, и почему видео ждёт. */
    private fun reportDownload(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Started ->
                Diary.note("видео ${event.item.name}: качаю ${placeName(event.place)} ${event.item.sizeBytes / 1_048_576} МБ")
            is DownloadEvent.Finished -> {
                val seconds = maxOf(1L, event.tookMillis / 1000)
                val speed = event.item.sizeBytes / 1_048_576.0 / seconds
                Diary.note(
                    "видео ${event.item.name} скачано ${placeName(event.place)} за ${formatSeconds(seconds)}, " +
                        "${"%.1f".format(speed)} МБ/с",
                )
            }
            is DownloadEvent.Failed -> {
                val tooLarge = event.reason.contains("too large", ignoreCase = true) ||
                    event.reason.contains("EFBIG")
                val why = if (tooLarge) {
                    "файл больше, чем принимает флешка (FAT32 берёт до 4 ГБ — нужна exFAT или NTFS)"
                } else {
                    event.reason
                }
                Diary.problem("видео ${event.item.name} не скачалось: $why")
            }
            is DownloadEvent.Undecodable ->
                Diary.problem("телевизор не декодирует ${event.item.name} (${event.codec}) — видео не качается и не показывается")
        }
    }

    private fun placeName(place: Storage.Place): String = when (place) {
        is Storage.Place.TvMemory -> "в память телевизора"
        is Storage.Place.Flash -> "на флешку ${place.label}"
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
        const val STORAGE_DIRECTORY = "storage"
        const val SCRATCH_DIRECTORY = "scratch"
        /** Кэш-времянка под «не хранить»: пара десятков снимков. */
        const val SCRATCH_BYTES = 32L * 1024 * 1024
        /** Кэш копий снимков сборок до 1.4 — переезжает в хранилище. */
        const val LEGACY_CACHE_DIRECTORY = "media"
        const val WATCHDOG_TICK_MILLIS = 20_000L
        const val CLOCK_SKEW_NOTE_MILLIS = 3_000L
        const val WATCHDOG_GRACE_MILLIS = 90_000L
        const val FLASH_CHECK_MILLIS = 10_000L
        const val HISTORY_DEPTH = 10
        const val SKIP_NOTE_INTERVAL_MILLIS = 60_000L

        /** Столько остановок на подкачку — и ролик пропускается. */
        const val STALLS_TO_NOTE = 3
        /** Сколько держать подсказку, вызванную с пульта. */
        const val GUIDE_FLASH_MILLIS = 10_000L
        /** «Без ограничения» для видео: сутки, которых не бывает. */
        const val UNLIMITED_VIDEO_MILLIS = 24L * 60 * 60 * 1000
    }
}
