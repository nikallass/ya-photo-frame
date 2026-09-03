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
import ru.dvedev.me.yaphotoframe.cache.MediaCache
import ru.dvedev.me.yaphotoframe.cache.MediaFetcher
import ru.dvedev.me.yaphotoframe.diag.Diary
import ru.dvedev.me.yaphotoframe.diag.ShowStats
import ru.dvedev.me.yaphotoframe.engine.FrameEngine
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
    private val history = ArrayDeque<ru.dvedev.me.yaphotoframe.media.MediaItem>()

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
                    scope.launch {
                        runCatching { engine?.sync()?.let(::report) }
                            .onFailure { Diary.problem("обход после смены отбора не удался", it) }
                    }
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
        slideshowJob = scope.launch { runSlideshow() }
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
        engine = null
        File(filesDir, LIBRARY_FILE).delete()
        File(cacheDir, CACHE_DIRECTORY).deleteRecursively()
        slideshowView?.clear()
        startSlideshow()
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
            // Движок при создании читает индекс с диска — на большой библиотеке
            // это четыре мегабайта JSON и почти три секунды. Делать это на
            // главном потоке значило бы замереть на старте заставки.
            val engine = withContext(Dispatchers.IO) {
                FrameEngine(
                source = YandexPublicDiskSource(
                    publicKey = store.current.folderUrl,
                    http = Http.client,
                    selection = { FolderSelection.of(store.current.selectedFolders) },
                    onProgress = { files, folders ->
                        indexing = if (folders < 0) null else Indexing(files, folders)
                    },
                ),
                store = LibraryStore(File(filesDir, LIBRARY_FILE)),
                folderStore = FolderIndexStore(File(filesDir, FOLDERS_FILE)),
                cache = cache,
                fetcher = MediaFetcher(Http.client, cache),
                policy = { store.current.cachePolicy() },
                includeVideo = { store.current.showVideo },
                minPhotoLongSide = ::minPhotoLongSide,
                measure = { _, file -> imageLongSide(file) },
                tuning = {
                    PlaylistTuning(
                        freshnessWindowMillis =
                        store.current.freshnessWindowDays.toLong() * 24 * 60 * 60 * 1000,
                    )
                },
                )
            }
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
                report(engine.sync())
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
                nextItem = engine::advance,
                previousItem = ::stepBack,
                preparer = preparer,
                showDurationMillis = ::holdMillis,
                pairPortraits = { store.current.pairPortraits },
                onShow = ::display,
                animateFirst = cameUpCold,
                fallbackItem = engine::cachedFallback,
                onSkip = ::noteSkip,
            )
            this.slideshow = slideshow
            coroutineScope { slideshow.run(this) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diary.problem("показ прерван", e)
            // Не достучались до хранилища при первом запуске — на экране должна
            // остаться подсказка, а не чернота.
            if (engine?.showablePhotos().isNullOrEmpty()) showGuide()
        }
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

    /** Идущий обход: сколько файлов и папок пройдено. Null — обход не идёт. */
    private class Indexing(val files: Int, val folders: Int)

    @Volatile
    private var indexing: Indexing? = null

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
        if (history.lastOrNull()?.path == item.path) return
        history.addLast(item)
        while (history.size > HISTORY_DEPTH) history.removeFirst()
    }

    /** Предыдущий показанный кадр; null — возвращаться некуда. */
    private fun stepBack(): ru.dvedev.me.yaphotoframe.media.MediaItem? {
        if (history.size < 2) return null
        history.removeLast()
        return history.lastOrNull()
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

            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
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
        // Плеер здесь не останавливаем: пока слой с роликом виден, он держит на
        // поверхности последний кадр. Отпустим его, когда слой уйдёт.
        if (prepared is PreparedVideo) startPlayback(prepared)

        Log.i(TAG, "показываю ${describe(prepared)}")
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
            onFirstFrame = { slideshowView?.hideVideoPoster() },
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
                onRescanFolders = {
                    scope.launch {
                        runCatching {
                            Diary.note("собираю список папок")
                            val found = engine?.rebuildFolderIndex() ?: 0
                            Diary.note("список папок собран: $found")
                        }.onFailure { Diary.problem("не собрал список папок", it) }
                    }
                },
                onRefresh = {
                    scope.launch {
                        runCatching { engine?.sync()?.let(::report) }
                            .onFailure { Diary.problem("обход по требованию не удался", it) }
                    }
                },
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
                indexing?.let { "{\"files\":${it.files},\"folders\":${it.folders}}" } ?: "null",
            )
            append("},")
            append("\"cache\":{")
            append("\"usedBytes\":").append(cache?.usedBytes ?: 0).append(',')
            append("\"budgetBytes\":").append(cache?.budgetBytes ?: 0).append(',')
            append("\"files\":").append(cache?.files ?: 0)
            append("},")
            append("\"queue\":").append(
                jsonArray(engine?.let { kotlinx.coroutines.runBlocking { it.upcoming() } }?.map { it.name }.orEmpty()),
            )
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
        const val HISTORY_DEPTH = 10
        const val SKIP_NOTE_INTERVAL_MILLIS = 60_000L
        /** Сколько держать подсказку, вызванную с пульта. */
        const val GUIDE_FLASH_MILLIS = 10_000L
        /** «Без ограничения» для ролика: сутки, которых не бывает. */
        const val UNLIMITED_VIDEO_MILLIS = 24L * 60 * 60 * 1000
    }
}
