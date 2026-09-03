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
        if (uuid.isBlank()) return "нет"
        val volume = runCatching { media.volume(uuid) }.getOrNull() ?: return "$uuid — не подключён"
        return "${volume.label} ${volume.uuid}, свободно ${volume.freeBytes / 1_073_741_824} ГБ"
    }

    /** Перебор по кругу: «нет» и все подключённые флешки. */
    private fun nextVolume(current: String, step: Int): String {
        val options = listOf("") + runCatching { media.volumes() }.getOrDefault(emptyList()).map { it.uuid }
        val index = options.indexOf(current).coerceAtLeast(0)
        return options[Math.floorMod(index + step, options.size)]
    }

    private class Row(
        val title: String,
        val hint: String,
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
        container.addView(label(cacheSummary(), 14f, MUTED))
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
        return "{\"index\":{\"total\":0},\"cache\":{\"usedBytes\":0,\"budgetBytes\":0,\"files\":0}," +
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
            foldersJson(children, index.builtAtMillis, index.folders.size)
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

    /** Занятость кэша считается прямо по директории: движок здесь не запущен. */
    private fun cacheSummary(): String {
        val directory = File(cacheDir, "media")
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        val used = files.sumOf { it.length() }
        return "Кэш: ${used / 1024 / 1024} МБ в ${files.size} файлах " +
            "из ${store.current.cacheBudgetBytes / 1024 / 1024} МБ"
    }

    private fun buildRows() {
        rows += Row(
            title = "Папка на Яндекс.Диске",
            hint = "публичная ссылка",
            show = { it.folderUrl.removePrefix(LINK_PREFIX).ifEmpty { "не указана" } },
            edit = { it.editFolderUrl() },
            step = { settings, _ -> settings },
        )
        rows += Row("Интервал показа", "сколько висит кадр", { format(it.showDurationMillis) }) { s, d ->
            s.copy(showDurationMillis = nudge(s.showDurationMillis, d, 5_000L))
        }
        rows += Row("Переход", "длительность смены", { format(it.crossfadeMillis) }) { s, d ->
            s.copy(crossfadeMillis = (s.crossfadeMillis + d * 250L).coerceIn(0L, 10_000L))
        }
        rows += Row("Длина хода", "насколько уезжает кадр", { percent(it.driftAmplitude) }) { s, d ->
            s.copy(driftAmplitude = s.driftAmplitude + d * 0.005f)
        }
        rows += Row("Приближение", "на сколько кадр вырастает", { percent(it.zoomAmount) }) { s, d ->
            s.copy(zoomAmount = s.zoomAmount + d * 0.01f)
        }
        rows += Row("Скорость приближения", "за минуту", { percent(it.zoomSpeedPerMinute) }) { s, d ->
            s.copy(zoomSpeedPerMinute = s.zoomSpeedPerMinute + d * 0.01f)
        }
        rows += Row("Скорость хода", "за минуту", { percent(it.driftSpeedPerMinute) }) { s, d ->
            s.copy(driftSpeedPerMinute = s.driftSpeedPerMinute + d * 0.005f)
        }
        rows += Row("Размер кадра", "доля экрана", { percent(it.frameInset) }) { s, d ->
            s.copy(frameInset = s.frameInset + d * 0.01f)
        }
        rows += Row("Смещение от центра", "золотое сечение", { percent(it.placementStrength) }) { s, d ->
            s.copy(placementStrength = s.placementStrength + d * 0.05f)
        }
        rows += Row("Затемнение фона", "0 — как есть", { percent(it.backgroundDim) }) { s, d ->
            s.copy(backgroundDim = s.backgroundDim + d * 0.05f)
        }
        rows += Row("Размытие фона", "меньше — сильнее", { "${it.blurSampleLongSide} пикс." }) { s, d ->
            s.copy(blurSampleLongSide = s.blurSampleLongSide + d)
        }
        rows += Row("Объём кэша", "место под скачанное", { megabytes(it.cacheBudgetBytes) }) { s, d ->
            s.copy(cacheBudgetBytes = s.cacheBudgetBytes + d * 64L * 1024 * 1024)
        }
        rows += Row("Порог кэширования", "тяжелее — потоком", { megabytes(it.cacheItemThresholdBytes) }) { s, d ->
            s.copy(cacheItemThresholdBytes = s.cacheItemThresholdBytes + d * 10L * 1024 * 1024)
        }
        rows += Row("Предзагрузка", "кадров вперёд", { "${it.prefetchCount}" }) { s, d ->
            s.copy(prefetchCount = s.prefetchCount + d)
        }
        rows += Row("Переобход папки", "не чаще чем раз в", { format(it.indexRefreshIntervalMillis) }) { s, d ->
            s.copy(indexRefreshIntervalMillis = s.indexRefreshIntervalMillis + d * 15L * 60 * 1000)
        }
        rows += Row("Видео", "показывать ролики", { yesNo(it.showVideo) },
            step = { s, _ -> s.copy(showVideo = !s.showVideo) })
        rows += Row("Ролик не дольше", "0 — до конца", { format(it.videoMaxDurationMillis) },
            step = { s, d -> s.copy(videoMaxDurationMillis = (s.videoMaxDurationMillis + d * 30_000L).coerceAtLeast(0L)) })
        rows += Row("Ролик не тяжелее", "0 — без ограничения",
            { if (it.videoMaxSizeBytes <= 0) "без ограничения" else "${it.videoMaxSizeBytes / 1_048_576} МБ" }) { s, d ->
            s.copy(videoMaxSizeBytes = (s.videoMaxSizeBytes + d * 128L * 1_048_576).coerceAtLeast(0L))
        }
        rows += Row("Подкачка потока", "тяжёлому ролику заранее; 0 — нет",
            { if (it.streamBufferBytes <= 0) "нет" else "${it.streamBufferBytes / 1_048_576} МБ" }) { s, d ->
            s.copy(streamBufferBytes = (s.streamBufferBytes + d * 128L * 1_048_576).coerceAtLeast(0L))
        }
        rows += Row("Канал", "выше — не потоком; 0 — всё потоком",
            { if (it.streamMaxBitrateBps <= 0) "без ограничения" else "${it.streamMaxBitrateBps / 1_000_000} Мбит/с" }) { s, d ->
            s.copy(streamMaxBitrateBps = (s.streamMaxBitrateBps + d * 5_000_000L).coerceAtLeast(0L))
        }
        rows += Row("Носитель", "флешка под тяжёлые ролики", { describeVolume(it.externalStorageUuid) }) { s, d ->
            s.copy(externalStorageUuid = nextVolume(s.externalStorageUuid, d))
        }
        rows += Row("Запас на носителе", "столько не занимать",
            { "%.1f ГБ".format(it.externalReserveBytes / 1_073_741_824.0) }) { s, d ->
            s.copy(externalReserveBytes = (s.externalReserveBytes + d * 536_870_912L).coerceAtLeast(0L))
        }
        rows += Row("Звук в роликах", "по умолчанию нет", { yesNo(it.videoSoundEnabled) },
            step = { s, _ -> s.copy(videoSoundEnabled = !s.videoSoundEnabled) })
        rows += Row("Минимальная ширина", "уже — не показывать",
            { if (it.minPhotoFraction <= 0f) "всё" else "${(it.minPhotoFraction * 100).toInt()} %" },
            step = { s, d -> s.copy(minPhotoFraction = (s.minPhotoFraction + d * 0.05f).coerceIn(0f, 0.6f)) })
        rows += Row("Пары вертикальных", "два снимка рядом", { yesNo(it.pairPortraits) },
            step = { s, _ -> s.copy(pairPortraits = !s.pairPortraits) })
        rows += Row("Пауза не дольше", "0 — пока не снимут", { if (it.pauseAutoResumeMillis <= 0) "бессрочно" else "${it.pauseAutoResumeMillis / 60_000} мин" }) { s, d ->
            s.copy(pauseAutoResumeMillis = (s.pauseAutoResumeMillis + d * 60_000L).coerceAtLeast(0L))
        }
        rows += Row("Часы", "поверх кадра", { yesNo(it.showClock) },
            step = { s, _ -> s.copy(showClock = !s.showClock) })
        rows += Row("Дата съёмки", "поверх кадра", { yesNo(it.showDate) },
            step = { s, _ -> s.copy(showDate = !s.showDate) })
        rows += Row(
            title = "Страница настройки",
            hint = "в домашней сети",
            show = { if (it.tunerEnabled) "включена" else "выключена" },
            step = { s, _ -> s.copy(tunerEnabled = !s.tunerEnabled) },
        )

        rows.forEach { row -> container.addView(rowView(row)) }
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
            row.view.text = "${row.title}   ·   ${row.show(settings)}\n${row.hint}"
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

    private fun buildButtons() {
        container.addView(
            button("Открыть системный выбор заставки") {
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
            button("Вернуть значения по умолчанию") {
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

    private fun megabytes(bytes: Long) = "${bytes / 1024 / 1024} МБ"

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
