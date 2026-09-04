package ru.dvedev.me.yaphotoframe.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.yaphotoframe.Defaults
import ru.dvedev.me.yaphotoframe.cache.CachePolicy
import ru.dvedev.me.yaphotoframe.ui.FrameSettings

/**
 * Настройки вида: хранение и оповещение об изменениях.
 *
 * Значения переживают перезагрузку телевизора, а подписчики узнают об изменении
 * сразу — это и есть смысл тюнера: ползунок двинулся, экран поменялся, никакой
 * пересборки и перезапуска.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    /**
     * Слушатель чужих записей.
     *
     * Настройки меняются из трёх мест — со страницы в браузере, с экрана на
     * телевизоре и через adb, — и у каждого свой экземпляр этого класса поверх
     * одних и тех же `SharedPreferences`. Без подписки копии в памяти
     * разъезжались бы: сделанное через adb не доходило бы до заставки, а
     * следующее движение ползунка затирало бы его устаревшим значением.
     *
     * Ссылка хранится полем намеренно: `SharedPreferences` держит слушателей
     * слабыми, и без неё подписку собрал бы сборщик мусора.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val fresh = read()
        if (fresh != state.value) state.value = fresh
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val settings: StateFlow<FrameSettings> = state.asStateFlow()

    val current: FrameSettings get() = state.value

    fun update(transform: (FrameSettings) -> FrameSettings) {
        val updated = transform(state.value).sanitized()
        write(updated)
        state.value = updated
    }

    fun reset() {
        prefs.edit().clear().commit()
        state.value = FrameSettings()
    }

    private fun read(): FrameSettings {
        val defaults = FrameSettings()
        return FrameSettings(
            folderUrl = prefs.getString(KEY_FOLDER, defaults.folderUrl) ?: defaults.folderUrl,
            showDurationMillis = prefs.getLong(KEY_SHOW, defaults.showDurationMillis),
            crossfadeMillis = prefs.getLong(KEY_CROSSFADE, defaults.crossfadeMillis),
            driftAmplitude = prefs.getFloat(KEY_DRIFT, defaults.driftAmplitude),
            zoomAmount = prefs.getFloat(KEY_ZOOM, defaults.zoomAmount),
            frameInset = prefs.getFloat(KEY_INSET, defaults.frameInset),
            edgeMargin = prefs.getFloat(KEY_MARGIN, defaults.edgeMargin),
            placementStrength = prefs.getFloat(KEY_PLACEMENT, defaults.placementStrength),
            backgroundDim = prefs.getFloat(KEY_DIM, defaults.backgroundDim),
            blurSampleLongSide = prefs.getInt(KEY_BLUR, defaults.blurSampleLongSide),
            minPhotoFraction = prefs.getFloat(KEY_MIN_PHOTO, defaults.minPhotoFraction),
            tunerEnabled = prefs.getBoolean(KEY_TUNER, defaults.tunerEnabled),
            cacheBudgetBytes = prefs.getLong(KEY_CACHE_BUDGET, defaults.cacheBudgetBytes),
            cacheItemThresholdBytes =
                prefs.getLong(KEY_CACHE_THRESHOLD, defaults.cacheItemThresholdBytes),
            selectedFolders = prefs.getStringSet(KEY_FOLDERS, defaults.selectedFolders)
                ?: defaults.selectedFolders,
            showVideo = prefs.getBoolean(KEY_VIDEO, defaults.showVideo),
            downloadsDuringVideo = prefs.getBoolean(KEY_DOWNLOADS_DURING_VIDEO, defaults.downloadsDuringVideo),
            videoMaxDurationMillis =
                prefs.getLong(KEY_VIDEO_MAX, defaults.videoMaxDurationMillis),
            videoSoundEnabled = prefs.getBoolean(KEY_VIDEO_SOUND, defaults.videoSoundEnabled),
            videoMaxSizeBytes = prefs.getLong(KEY_VIDEO_MAX_SIZE, defaults.videoMaxSizeBytes),
            streamBufferBytes = prefs.getLong(KEY_STREAM_BUFFER, defaults.streamBufferBytes),
            streamMaxBitrateBps = prefs.getLong(KEY_STREAM_BITRATE, defaults.streamMaxBitrateBps),
            externalStorageUuid = prefs.getString(KEY_EXTERNAL, defaults.externalStorageUuid)
                ?: defaults.externalStorageUuid,
            externalReserveBytes = prefs.getLong(KEY_EXTERNAL_RESERVE, defaults.externalReserveBytes),
            pairPortraits = prefs.getBoolean(KEY_PAIRS, defaults.pairPortraits),
            freshnessWindowDays = prefs.getInt(KEY_FRESHNESS, defaults.freshnessWindowDays),
            showClock = prefs.getBoolean(KEY_CLOCK, defaults.showClock),
            pauseAutoResumeMillis = prefs.getLong(KEY_PAUSE_RESUME, defaults.pauseAutoResumeMillis),
            showDate = prefs.getBoolean(KEY_DATE, defaults.showDate),
            prefetchCount = prefs.getInt(KEY_PREFETCH, defaults.prefetchCount),
            indexRefreshIntervalMillis =
                prefs.getLong(KEY_REFRESH, defaults.indexRefreshIntervalMillis),
        ).sanitized()
    }

    /**
     * Пишет настройки на диск немедленно.
     *
     * Именно `commit`, а не `apply`: `apply` кладёт значения в память и сбрасывает
     * их на диск когда-нибудь потом, а телевизор выключают из розетки, и процесс
     * заставки система гасит без предупреждения. Проверено: после жёсткого
     * убийства процесса записанные через `apply` настройки возвращались старыми.
     * Записей здесь единицы в секунду и по десятку чисел, синхронная запись
     * ничего не стоит, а идёт она с потока сервера, не с главного.
     */
    private fun write(value: FrameSettings) {
        prefs.edit()
            .putString(KEY_FOLDER, value.folderUrl)
            .putLong(KEY_SHOW, value.showDurationMillis)
            .putLong(KEY_CROSSFADE, value.crossfadeMillis)
            .putFloat(KEY_DRIFT, value.driftAmplitude)
            .putFloat(KEY_ZOOM, value.zoomAmount)
            .putFloat(KEY_INSET, value.frameInset)
            .putFloat(KEY_MARGIN, value.edgeMargin)
            .putFloat(KEY_PLACEMENT, value.placementStrength)
            .putFloat(KEY_DIM, value.backgroundDim)
            .putInt(KEY_BLUR, value.blurSampleLongSide)
            .putFloat(KEY_MIN_PHOTO, value.minPhotoFraction)
            .putBoolean(KEY_TUNER, value.tunerEnabled)
            .putLong(KEY_CACHE_BUDGET, value.cacheBudgetBytes)
            .putLong(KEY_CACHE_THRESHOLD, value.cacheItemThresholdBytes)
            .putStringSet(KEY_FOLDERS, value.selectedFolders)
            .putBoolean(KEY_VIDEO, value.showVideo)
            .putBoolean(KEY_DOWNLOADS_DURING_VIDEO, value.downloadsDuringVideo)
            .putLong(KEY_VIDEO_MAX, value.videoMaxDurationMillis)
            .putBoolean(KEY_VIDEO_SOUND, value.videoSoundEnabled)
            .putLong(KEY_VIDEO_MAX_SIZE, value.videoMaxSizeBytes)
            .putLong(KEY_STREAM_BUFFER, value.streamBufferBytes)
            .putLong(KEY_STREAM_BITRATE, value.streamMaxBitrateBps)
            .putString(KEY_EXTERNAL, value.externalStorageUuid)
            .putLong(KEY_EXTERNAL_RESERVE, value.externalReserveBytes)
            .putBoolean(KEY_PAIRS, value.pairPortraits)
            .putInt(KEY_FRESHNESS, value.freshnessWindowDays)
            .putBoolean(KEY_CLOCK, value.showClock)
            .putLong(KEY_PAUSE_RESUME, value.pauseAutoResumeMillis)
            .putBoolean(KEY_DATE, value.showDate)
            .putInt(KEY_PREFETCH, value.prefetchCount)
            .putLong(KEY_REFRESH, value.indexRefreshIntervalMillis)
            .commit()
    }

    private companion object {
        const val NAME = "visual-settings"
        const val KEY_FOLDER = "folder_url"
        const val KEY_SHOW = "show_duration_millis"
        const val KEY_CROSSFADE = "crossfade_millis"
        const val KEY_DRIFT = "drift_amplitude"
        const val KEY_ZOOM = "zoom_amount"
        const val KEY_INSET = "frame_inset"
        const val KEY_MARGIN = "edge_margin"
        const val KEY_PLACEMENT = "placement_strength"
        const val KEY_DIM = "background_dim"
        const val KEY_BLUR = "blur_sample_long_side"
        const val KEY_TUNER = "tuner_enabled"
        const val KEY_CACHE_BUDGET = "cache_budget_bytes"
        const val KEY_CACHE_THRESHOLD = "cache_item_threshold_bytes"
        const val KEY_PREFETCH = "prefetch_count"
        const val KEY_REFRESH = "index_refresh_interval_millis"
        const val KEY_FOLDERS = "selected_folders"
        const val KEY_VIDEO = "show_video"
        const val KEY_DOWNLOADS_DURING_VIDEO = "downloads_during_video"
        const val KEY_VIDEO_MAX = "video_max_duration_millis"
        const val KEY_VIDEO_SOUND = "video_sound_enabled"
        const val KEY_VIDEO_MAX_SIZE = "video_max_size_bytes"
        const val KEY_STREAM_BUFFER = "stream_buffer_bytes"
        const val KEY_STREAM_BITRATE = "stream_max_bitrate_bps"
        const val KEY_EXTERNAL = "external_storage_uuid"
        const val KEY_EXTERNAL_RESERVE = "external_reserve_bytes"
        const val KEY_PAIRS = "pair_portraits"
        const val KEY_FRESHNESS = "freshness_window_days"
        const val KEY_MIN_PHOTO = "min_photo_fraction"
        const val KEY_CLOCK = "show_clock"
        const val KEY_PAUSE_RESUME = "pause_auto_resume_millis"
        const val KEY_DATE = "show_date"
    }
}

/**
 * Держит значения в осмысленных пределах: тюнер отдаёт что угодно.
 *
 * Порог кэша прижимается к **уже нормализованному** бюджету, а не к тому, что
 * пришло. Иначе при бюджете меньше мегабайта диапазон получался пустым, и
 * `coerceIn` бросал исключение прямо посреди обработки запроса — страница
 * настройки отвечала пустотой, а причина выглядела как поломка сети.
 */
fun FrameSettings.sanitized(): FrameSettings {
    val budget = cacheBudgetBytes.coerceIn(
        CachePolicy.MIN_BUDGET_BYTES,
        CachePolicy.MAX_BUDGET_BYTES,
    )
    return FrameSettings(
        // Ссылка приходит из ввода пультом и из adb: и пробелы, и полный адрес
        // с лишним хвостом — обычное дело.
        folderUrl = folderUrl.trim(),
        showDurationMillis = showDurationMillis.coerceIn(
            FrameSettings.MIN_SHOW_DURATION_MILLIS,
            FrameSettings.MAX_SHOW_DURATION_MILLIS,
        ),
        crossfadeMillis = crossfadeMillis.coerceIn(0L, 10_000L),
        driftAmplitude = driftAmplitude.coerceIn(0f, 0.30f),
        zoomAmount = zoomAmount.coerceIn(0f, 0.30f),
        frameInset = frameInset.coerceIn(0.3f, 1f),
        edgeMargin = edgeMargin.coerceIn(0f, 0.25f),
        placementStrength = placementStrength.coerceIn(0f, 1f),
        backgroundDim = backgroundDim.coerceIn(0f, 1f),
        blurSampleLongSide = blurSampleLongSide.coerceIn(2, 64),
        // Выше двух третей отсеклись бы и полноразмерные снимки: копия с Диска
        // не больше 1280 px.
        minPhotoFraction = minPhotoFraction.coerceIn(0f, 0.6f),
        tunerEnabled = tunerEnabled,
        cacheBudgetBytes = budget,
        cacheItemThresholdBytes = cacheItemThresholdBytes.coerceIn(MIN_ITEM_THRESHOLD_BYTES, budget),
        // Пустые строки в наборе сломали бы отбор: пустой префикс совпадает
        // со всем подряд, и «выбрано ничего» превратилось бы в «выбрано всё».
        selectedFolders = selectedFolders.filter { it.isNotBlank() }.toSet(),
        showVideo = showVideo,
        downloadsDuringVideo = downloadsDuringVideo,
        videoMaxDurationMillis = videoMaxDurationMillis.coerceIn(0L, 60L * 60 * 1000),
        videoSoundEnabled = videoSoundEnabled,
        videoMaxSizeBytes = videoMaxSizeBytes.coerceIn(0L, 8L * 1024 * 1024 * 1024),
        streamBufferBytes = streamBufferBytes.coerceIn(0L, 4L * 1024 * 1024 * 1024),
        streamMaxBitrateBps = streamMaxBitrateBps.coerceIn(0L, 2_000_000_000L),
        externalStorageUuid = externalStorageUuid.trim(),
        externalReserveBytes = externalReserveBytes.coerceIn(0L, 1024L * 1024 * 1024 * 1024),
        pairPortraits = pairPortraits,
        freshnessWindowDays = freshnessWindowDays.coerceIn(1, 3650),
        showClock = showClock,
        pauseAutoResumeMillis = pauseAutoResumeMillis.coerceIn(0L, 24L * 60 * 60 * 1000),
        showDate = showDate,
        prefetchCount = prefetchCount.coerceIn(1, 50),
        // Не реже трёх часов: столько живут ссылки Диска на превью.
        indexRefreshIntervalMillis = indexRefreshIntervalMillis.coerceIn(
            60_000L,
            3L * 60 * 60 * 1000,
        ),
    )
}

/** Ниже этого порога кэшировать нечего: столько весит одна уменьшенная копия. */
private const val MIN_ITEM_THRESHOLD_BYTES = 1L * 1024 * 1024
