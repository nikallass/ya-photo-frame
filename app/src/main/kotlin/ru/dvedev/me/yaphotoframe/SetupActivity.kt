package ru.dvedev.me.yaphotoframe

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.dvedev.me.yaphotoframe.library.FolderIndex
import ru.dvedev.me.yaphotoframe.library.FolderIndexStore
import ru.dvedev.me.yaphotoframe.library.LibraryStore
import ru.dvedev.me.yaphotoframe.media.yandex.YandexPublicDiskSource
import ru.dvedev.me.yaphotoframe.settings.SettingsStore
import ru.dvedev.me.yaphotoframe.settings.SettingsUi
import ru.dvedev.me.yaphotoframe.tuner.TunerServer
import ru.dvedev.me.yaphotoframe.tuner.foldersJson
import ru.dvedev.me.yaphotoframe.tuner.jsonEscape
import ru.dvedev.me.yaphotoframe.ui.FrameSettings
import ru.dvedev.me.yaphotoframe.ui.GuideView
import java.io.File

/**
 * Настройка на самом телевизоре — для случая, когда ни телефона, ни ноутбука
 * под рукой нет.
 *
 * Сделана на обычных `View`, а не на Compose: экран простой, а лишний рантайм на
 * устройстве с двумя гигабайтами не окупается. Управление ровно пультом —
 * вверх-вниз по строкам, влево-вправо меняет значение, «ОК» открывает ввод.
 */
class SetupActivity : Activity() {

    private val store: SettingsStore by lazy { SettingsStore(this) }
    private lateinit var container: LinearLayout
    private val rows = mutableListOf<Row>()

    /**
     * Пока открыт этот экран, страница в браузере тоже поднята.
     *
     * Иначе она жила бы только во время заставки: телевизор смотрят — настроить
     * нельзя, а это ровно тот момент, когда хочется заглянуть в состояние.
     */
    private var tuner: TunerServer? = null

    /**
     * Строка настройки: как показать значение и как его подвинуть.
     *
     * Шаги заданы так же, как ползунки на странице в браузере, — чтобы
     * настройка с пульта и с телефона давали одно и то же.
     */
    private val media by lazy { ru.dvedev.me.yaphotoframe.storage.ExternalMedia(this) }

    private fun describeVolume(uuid: String): String {
        if (uuid.isBlank()) return "память телевизора, свободно ${cacheDir.usableSpace / 1_073_741_824} ГБ"
        val volume = runCatching { media.volume(uuid) }.getOrNull()
            ?: return "флешка $uuid не подключена — пока память телевизора"
        volume.problem?.let { return "${volume.label}: $it — пока память телевизора" }
        return "флешка ${volume.label} ${volume.uuid}, свободно ${volume.freeBytes / 1_073_741_824} ГБ"
    }

    /** Размер диска, где хранилище: потолок для объёма и запаса. */
    private fun placeTotalBytes(uuid: String): Long {
        if (uuid.isBlank()) return cacheDir.totalSpace
        return runCatching { media.volume(uuid) }.getOrNull()?.totalBytes?.takeIf { it > 0 } ?: cacheDir.totalSpace
    }

    /** Перебор по кругу: память телевизора и все подключённые флешки. */
    private fun nextVolume(current: String, step: Int): String {
        val options = listOf("") +
            runCatching { media.volumes() }.getOrDefault(emptyList()).filter { it.usable }.map { it.uuid }
        val index = options.indexOf(current).coerceAtLeast(0)
        return options[Math.floorMod(index + step, options.size)]
    }

    private class Row(
        /** Заголовок и строка под ним зависят от настроек: «Объём» становится «Запасом». */
        val title: (FrameSettings) -> String,
        val hint: (FrameSettings) -> String,
        val show: (FrameSettings) -> String,
        val edit: ((SetupActivity) -> Unit)? = null,
        // Последним параметром, чтобы описание строки читалось как одно целое:
        // название, подсказка, как показать, как подвинуть.
        val step: (FrameSettings, Int) -> FrameSettings,
    ) {
        lateinit var view: TextView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                isFillViewport = true
                addView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        )

        if (store.current.tunerEnabled) {
            tuner = TunerServer(
                store = store,
                assets = assets,
                host = "app",
                diagnostics = ::diagnostics,
                folders = ::foldersJson,
                onRescanFolders = ::rescanFolders,
                hasVolume = { uuid -> runCatching { media.volumes() }.getOrDefault(emptyList()).any { it.uuid == uuid } },
            ).also { it.start() }
        }

        buildHeader()
        buildRows()
        buildButtons()
        container.addView(label("Фоторамка ${BuildConfig.VERSION_NAME}", 12f, MUTED))
        refresh()
    }

    override fun onDestroy() {
        tuner?.stop()
        tuner = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Значения могли поменяться с телефона, пока экран был свёрнут.
        refresh()
    }

    private fun buildHeader() {
        // Пока папка не указана, инструкция важнее списка настроек: без неё
        // владелец не поймёт, с чего начинать.
        if (store.current.folderUrl.isBlank()) {
            container.addView(
                GuideView(
                    context = this,
                    addresses = if (store.current.tunerEnabled) tuner?.addresses().orEmpty()
                    else emptyList(),
                    showingDemo = false,
                    donateUrl = Defaults.DONATE_URL,
                    assignStep = true,
                    version = BuildConfig.VERSION_NAME,
                    // На этом экране подсказка делит место с настройками, но
                    // целиком должна влезать в экран без прокрутки.
                    maxHeightPx = resources.displayMetrics.heightPixels - PADDING * 2,
                )
            )
            container.addView(label("Или настройте прямо здесь, пультом", 20f, TEXT))
            return
        }

        container.addView(label("Фоторамка", 26f, TEXT))
        container.addView(
            label(
                "Вверх-вниз — по строкам, влево-вправо — меняет значение, «ОК» — ввод.",
                14f,
                MUTED,
            )
        )

        val addresses = TunerServer(store, assets).addresses()
        if (store.current.tunerEnabled && addresses.isNotEmpty()) {
            container.addView(
                label(
                    "Настройка с телефона: " + addresses.joinToString("  ") { it.url },
                    14f,
                    ACCENT,
                )
            )
        }
        container.addView(label(storageSummary(), 14f, MUTED))
    }

    /**
     * Состояние для страницы, пока заставка не запущена.
     *
     * Раньше страница из приложения отвечала на это «не достучался до
     * телевизора» — из-за чего при первой настройке казалось, что всё сломано.
     * Индекс здесь читается с диска, без движка, и только если файл менялся.
     */
    private var indexSummary: Pair<Long, String>? = null

    private fun diagnostics(): String {
        val file = File(filesDir, "library.json")
        val stamp = if (file.isFile) file.lastModified() else 0L
        val summary = indexSummary?.takeIf { it.first == stamp }?.second ?: run {
            val snapshot = LibraryStore(file).load()
            val text = if (snapshot.entries.isEmpty()) "индекс ещё не построен"
            else "в индексе ${snapshot.entries.size} файлов"
            indexSummary = stamp to text
            text
        }
        val status = if (store.current.folderUrl.isBlank()) {
            "Папка не задана. Задайте её здесь, затем назначьте заставку в настройках телевизора."
        } else {
            "Заставка сейчас не запущена ($summary). Обход и показ начнутся, когда она включится."
        }
        return "{\"index\":{\"total\":0},\"storage\":{\"place\":\"tv\",\"usedBytes\":0,\"budgetBytes\":0}," +
            "\"status\":{\"phase\":\"app\",\"text\":\"" + jsonEscape(status) + "\"}," +
            "\"queue\":[],\"hourly\":[],\"shows\":0,\"log\":[],\"failures\":[],\"errors\":[]}"
    }

    /** Дерево папок с того же файла, что и у заставки: раскрытые уровни общие. */
    private val folderStore by lazy { FolderIndexStore(File(filesDir, "folders.json")) }

    private fun source() = YandexPublicDiskSource(
        publicKey = store.current.folderUrl,
        http = Http.client,
    )

    private fun foldersJson(query: String): String {
        if (store.current.folderUrl.isBlank()) return foldersJson(emptyList(), 0, 0)
        val path = query.split('&')
            .firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: "/"
        return try {
            var index = folderStore.load()
            val children = index.childrenOf(path) ?: kotlinx.coroutines.runBlocking {
                source().subfolders(path)
            }.also {
                index = index.withLevel(path, it, System.currentTimeMillis())
                folderStore.save(index)
            }
            foldersJson(children, index.builtAtMillis, index.folders.size) { index.childrenOf(it)?.size }
        } catch (e: Exception) {
            Log.w(TAG, "не смог перечислить подпапки «$path»", e)
            foldersJson(emptyList(), 0, 0)
        }
    }

    private fun rescanFolders() {
        if (store.current.folderUrl.isBlank()) return
        Thread {
            runCatching {
                val folders = kotlinx.coroutines.runBlocking { source().allFolders() }
                folderStore.save(
                    FolderIndex(
                        builtAtMillis = System.currentTimeMillis(),
                        folders = folders,
                        scanned = folders.mapTo(mutableSetOf("/")) { it.path },
                    ),
                )
            }.onFailure { Log.w(TAG, "не собрал список папок", it) }
        }.start()
    }

    /** Занятость хранилища считается прямо по папке в памяти телевизора: движок здесь не запущен. */
    private fun storageSummary(): String {
        val directory = File(cacheDir, "storage")
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        val used = files.sumOf { it.length() }
        val place = if (store.current.storageVolumeUuid.isBlank()) "память телевизора" else "флешка"
        return "Хранилище: $place; в памяти телевизора ${used / 1024 / 1024} МБ в ${files.size} файлах"
    }

    /** Как показать и подвинуть значение по ключу настройки; тексты — из settings-ui.json. */
    private class Editor(
        val show: (FrameSettings) -> String,
        val edit: ((SetupActivity) -> Unit)? = null,
        val step: (FrameSettings, Int) -> FrameSettings,
    )

    private fun toggle(show: (FrameSettings) -> Boolean, flip: (FrameSettings) -> FrameSettings) =
        Editor(show = { yesNo(show(it)) }, step = { s, _ -> flip(s) })

    private fun editors(): Map<String, Editor> = mapOf(
        "folderUrl" to Editor(
            show = { it.folderUrl.removePrefix(LINK_PREFIX).ifEmpty { "не указана" } },
            edit = { it.editFolderUrl() },
            step = { settings, _ -> settings },
        ),
        "showDurationMillis" to Editor({ format(it.showDurationMillis) }) { s, d ->
            s.copy(showDurationMillis = nudge(s.showDurationMillis, d, 5_000L))
        },
        "crossfadeMillis" to Editor({ format(it.crossfadeMillis) }) { s, d ->
            s.copy(crossfadeMillis = (s.crossfadeMillis + d * 250L).coerceIn(0L, 10_000L))
        },
        "pauseAutoResumeMillis" to Editor(
            { if (it.pauseAutoResumeMillis <= 0) "пока не снимут" else "${it.pauseAutoResumeMillis / 60_000} мин" },
        ) { s, d ->
            s.copy(pauseAutoResumeMillis = (s.pauseAutoResumeMillis + d * 60_000L).coerceAtLeast(0L))
        },
        "driftAmplitude" to Editor({ if (it.driftAmplitude <= 0f) "неподвижен" else percent(it.driftAmplitude) }) { s, d ->
            s.copy(driftAmplitude = s.driftAmplitude + d * 0.005f)
        },
        "zoomAmount" to Editor({ if (it.zoomAmount <= 0f) "нет" else percent(it.zoomAmount) }) { s, d ->
            s.copy(zoomAmount = s.zoomAmount + d * 0.01f)
        },
        "frameInsetLandscape" to Editor({ percent(it.frameInsetLandscape) }) { s, d ->
            s.copy(frameInsetLandscape = s.frameInsetLandscape + d * 0.01f)
        },
        "frameInsetPortrait" to Editor({ percent(it.frameInsetPortrait) }) { s, d ->
            s.copy(frameInsetPortrait = s.frameInsetPortrait + d * 0.01f)
        },
        "placementStrength" to Editor({ percent(it.placementStrength) }) { s, d ->
            s.copy(placementStrength = s.placementStrength + d * 0.05f)
        },
        "edgeMargin" to Editor({ percent(it.edgeMargin) }) { s, d ->
            s.copy(edgeMargin = s.edgeMargin + d * 0.01f)
        },
        "backgroundDim" to Editor({ percent(it.backgroundDim) }) { s, d ->
            s.copy(backgroundDim = s.backgroundDim + d * 0.05f)
        },
        // В настройке пиксели фона (меньше — размытее), на экране сила размытия
        // в процентах: вправо — сильнее, как и на странице.
        "blurSampleLongSide" to Editor({ "${blurStrength(it.blurSampleLongSide)} %" }) { s, d ->
            s.copy(blurSampleLongSide = (s.blurSampleLongSide - d * 3).coerceIn(2, 64))
        },
        "showVideo" to toggle({ it.showVideo }) { it.copy(showVideo = !it.showVideo) },
        "videoSoundEnabled" to toggle({ it.videoSoundEnabled }) { it.copy(videoSoundEnabled = !it.videoSoundEnabled) },
        "downloadsDuringVideo" to toggle({ it.downloadsDuringVideo }) { it.copy(downloadsDuringVideo = !it.downloadsDuringVideo) },
        "pairPortraits" to toggle({ it.pairPortraits }) { it.copy(pairPortraits = !it.pairPortraits) },
        "minPhotoFraction" to Editor(
            { if (it.minPhotoFraction <= 0f) "показывать всё" else "${(it.minPhotoFraction * 100).toInt()} % экрана" },
        ) { s, d -> s.copy(minPhotoFraction = (s.minPhotoFraction + d * 0.05f).coerceIn(0f, 0.6f)) },
        "freshnessWindowDays" to Editor({ "${it.freshnessWindowDays} дн." }) { s, d ->
            s.copy(freshnessWindowDays = (s.freshnessWindowDays + d).coerceIn(1, 3650))
        },
        "showClock" to toggle({ it.showClock }) { it.copy(showClock = !it.showClock) },
        "showDate" to toggle({ it.showDate }) { it.copy(showDate = !it.showDate) },
        "videoMaxDurationMillis" to Editor({ format(it.videoMaxDurationMillis) }) { s, d ->
            s.copy(videoMaxDurationMillis = (s.videoMaxDurationMillis + d * 30_000L).coerceAtLeast(0L))
        },
        "maxFileBytes" to Editor(
            { if (it.maxFileBytes <= 0) "без ограничения" else size(it.maxFileBytes) },
        ) { s, d -> s.copy(maxFileBytes = (s.maxFileBytes + d * 128L * 1_048_576).coerceAtLeast(0L)) },
        "minStorePhotoBytes" to Editor(
            { if (it.minStorePhotoBytes <= 0) "хранить все" else size(it.minStorePhotoBytes) },
        ) { s, d -> s.copy(minStorePhotoBytes = (s.minStorePhotoBytes + d * 16L * 1024).coerceAtLeast(0L)) },
        "minStoreVideoBytes" to Editor(
            { if (it.minStoreVideoBytes <= 0) "хранить все" else size(it.minStoreVideoBytes) },
        ) { s, d -> s.copy(minStoreVideoBytes = (s.minStoreVideoBytes + d * 16L * 1_048_576).coerceAtLeast(0L)) },
        "storageVolumeUuid" to Editor({ describeVolume(it.storageVolumeUuid) }) { s, d ->
            s.copy(storageVolumeUuid = nextVolume(s.storageVolumeUuid, d))
        },
        // Один бегунок с двумя смыслами: объём, а при «по свободному месту» — запас.
        "storageBytes" to Editor(
            { if (it.storageByFree) size(it.storageReserveBytes) else size(it.storageBytes) },
        ) { s, d ->
            val step = d * 256L * 1_048_576
            val ceiling = placeTotalBytes(s.storageVolumeUuid)
            if (s.storageByFree) s.copy(storageReserveBytes = (s.storageReserveBytes + step).coerceIn(0L, ceiling))
            else s.copy(storageBytes = (s.storageBytes + step).coerceIn(0L, ceiling))
        },
        "storageByFree" to toggle({ it.storageByFree }) { it.copy(storageByFree = !it.storageByFree) },
        "networkBps" to Editor(
            { if (it.networkBps <= 0) "авто" else "${it.networkBps / 1_000_000} Мбит/с" },
        ) { s, d -> s.copy(networkBps = (s.networkBps + d * 5_000_000L).coerceAtLeast(0L)) },
        "prefetchCount" to Editor({ "${it.prefetchCount}" }) { s, d ->
            s.copy(prefetchCount = s.prefetchCount + d)
        },
        "indexRefreshIntervalMillis" to Editor({ format(it.indexRefreshIntervalMillis) }) { s, d ->
            s.copy(indexRefreshIntervalMillis = s.indexRefreshIntervalMillis + d * 15L * 60 * 1000)
        },
        "tunerEnabled" to Editor(
            show = { if (it.tunerEnabled) "включена" else "выключена" },
            step = { s, _ -> s.copy(tunerEnabled = !s.tunerEnabled) },
        ),
    )

    private fun blurStrength(pixels: Int) = Math.round((64 - pixels) * 100f / 62)

    /**
     * Строки — по тому же файлу, что и страница: те же разделы, порядок и слова.
     * Заголовок раздела — обычная подпись без фокуса, пульт её перешагивает.
     */
    private fun buildRows() {
        val ui = this.ui
        val editors = editors()
        val sections = ui.sections.filter { it.items.isNotEmpty() } + listOfNotNull(ui.app)
        for (section in sections) {
            if (section.title.isNotBlank()) {
                container.addView(label(section.title, 20f, TEXT).apply { setPadding(0, PADDING, 0, 0) })
                if (section.note.isNotBlank()) container.addView(label(section.note, 13f, MUTED))
            }
            for (item in section.items) {
                for (row in rowsFor(item, editors)) {
                    rows += row
                    container.addView(rowView(row))
                }
            }
        }
    }

    /**
     * Строки из одного описания: обычная — одна, «два бегунка» — по строке на
     * ключ с подписью, «объём с переключателем» — бегунок с меняющимся
     * названием и отдельная строка переключателя.
     */
    private fun rowsFor(item: SettingsUi.Item, editors: Map<String, Editor>): List<Row> {
        fun row(key: String, title: (FrameSettings) -> String, hint: (FrameSettings) -> String): Row? {
            val editor = editors[key]
            if (editor == null) {
                Log.e(TAG, "в settings-ui.json есть $key, а редактора для него нет")
                return null
            }
            return Row(title, hint, editor.show, editor.edit, editor.step)
        }
        return when (item.kind) {
            "dual" -> {
                val labels = listOf(item.labels["first"].orEmpty(), item.labels["second"].orEmpty())
                item.keys().mapIndexedNotNull { i, key ->
                    row(key, { "${item.title}: ${labels.getOrElse(i) { "" }}" }, { item.note })
                }
            }
            "capacity" -> listOfNotNull(
                row(
                    item.key,
                    { if (it.storageByFree) item.alt?.title ?: item.title else item.title },
                    { if (it.storageByFree) item.alt?.note ?: item.note else item.note },
                ),
                row("storageByFree", { item.labels["toggle"] ?: "По свободному месту" }, { "" }),
            )
            else -> listOfNotNull(row(item.key, { item.title }, { item.note }))
        }
    }

    private fun rowView(row: Row): View {
        val view = TextView(this).apply {
            textSize = 18f
            setTextColor(TEXT)
            setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(CARD)
            setOnFocusChangeListener { _, focused ->
                setBackgroundColor(if (focused) FOCUS else CARD)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { change(row, -1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { change(row, +1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        row.edit?.invoke(this@SetupActivity)
                        row.edit != null
                    }

                    else -> false
                }
            }
        }
        row.view = view
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = PADDING / 4 }
        view.layoutParams = params
        return view
    }

    private fun change(row: Row, direction: Int) {
        store.update { row.step(it, direction) }
        refresh()
    }

    private fun refresh() {
        val settings = store.current
        rows.forEach { row ->
            val hint = row.hint(settings)
            row.view.text = "${row.title(settings)}   ·   ${row.show(settings)}" + if (hint.isBlank()) "" else "\n$hint"
        }
    }

    private fun editFolderUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(store.current.folderUrl.ifEmpty { LINK_PREFIX })
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Публичная ссылка на папку")
            .setMessage("Схема и домен уже вписаны — доберите только код после /d/")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                store.update { it.copy(folderUrl = input.text.toString()) }
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Тексты — те же, что на странице; читаются один раз на экран. */
    private val ui: SettingsUi by lazy {
        SettingsUi.parse(assets.open(SettingsUi.ASSET).bufferedReader().use { it.readText() })
    }

    private fun buildButtons() {
        container.addView(
            button(ui.buttons["dreamSettings"]?.title ?: "Открыть системный выбор заставки") {
                if (!tryOpenDreamSettings()) {
                    AlertDialog.Builder(this)
                        .setTitle("Системный экран недоступен")
                        .setMessage(
                            "На этом телевизоре его нет. Выберите «Фоторамка» в настройках " +
                                "заставки телевизора или назначьте её через adb.",
                        )
                        .setPositiveButton("Понятно", null)
                        .show()
                }
            }
        )
        container.addView(
            button(ui.buttons["reset"]?.title ?: "Вернуть значения по умолчанию") {
                store.reset()
                refresh()
            }
        )
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = PADDING / 2 }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        setPadding(0, PADDING / 4, 0, PADDING / 4)
    }

    private fun tryOpenDreamSettings(): Boolean = try {
        startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "экран выбора заставки не резолвится на этом устройстве", e)
        false
    }

    private fun nudge(value: Long, direction: Int, step: Long): Long =
        // Секунды у нижнего края и минуты у верхнего: линейный шаг сделал бы
        // короткие интервалы недостижимыми, а длинные — бесконечно долгими.
        when {
            value < 60_000L -> value + direction * step
            value < 600_000L -> value + direction * 30_000L
            else -> value + direction * 300_000L
        }

    private fun format(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds с"
            seconds < 3600 -> "${seconds / 60} мин"
            else -> "${seconds / 3600} ч"
        }
    }

    private fun yesNo(value: Boolean) = if (value) "да" else "нет"

    private fun percent(value: Float) = "${Math.round(value * 100)} %"

    private fun size(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "${bytes / 1_048_576} МБ"
        else -> "${bytes / 1024} КБ"
    }

    private companion object {
        const val TAG = "YaPhotoFrame"
        const val LINK_PREFIX = "https://disk.yandex.ru/d/"
        const val PADDING = 40
        const val BACKGROUND = 0xFF14161C.toInt()
        const val CARD = 0xFF1E222B.toInt()
        const val FOCUS = 0xFF39404F.toInt()
        const val TEXT = 0xFFE8E6E1.toInt()
        const val MUTED = 0xFF9AA1AE.toInt()
        const val ACCENT = 0xFFD9A441.toInt()
    }
}
