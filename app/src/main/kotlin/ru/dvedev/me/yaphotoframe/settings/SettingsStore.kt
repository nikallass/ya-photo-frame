package ru.dvedev.me.yaphotoframe.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dvedev.me.yaphotoframe.Defaults
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
            // Старый единый размер кадра переезжает в размер горизонтальных,
            // чтобы обновление ничего не сбросило.
            frameInsetLandscape = prefs.getFloat(
                KEY_INSET_LANDSCAPE,
                prefs.getFloat(KEY_INSET, defaults.frameInsetLandscape),
            ),
            frameInsetPortrait = prefs.getFloat(KEY_INSET_PORTRAIT, defaults.frameInsetPortrait),
            edgeMargin = prefs.getFloat(KEY_MARGIN, defaults.edgeMargin),
            placementStrength = prefs.getFloat(KEY_PLACEMENT, defaults.placementStrength),
            backgroundDim = prefs.getFloat(KEY_DIM, defaults.backgroundDim),
            blurSampleLongSide = prefs.getInt(KEY_BLUR, defaults.blurSampleLongSide),
            minPhotoFraction = prefs.getFloat(KEY_MIN_PHOTO, defaults.minPhotoFraction),
            tunerEnabled = prefs.getBoolean(KEY_TUNER, defaults.tunerEnabled),
            selectedFolders = prefs.getStringSet(KEY_FOLDERS, defaults.selectedFolders)
                ?: defaults.selectedFolders,
            showVideo = prefs.getBoolean(KEY_VIDEO, defaults.showVideo),
            downloadsDuringVideo = prefs.getBoolean(KEY_DOWNLOADS_DURING_VIDEO, defaults.downloadsDuringVideo),
            videoMaxDurationMillis =
                prefs.getLong(KEY_VIDEO_MAX, defaults.videoMaxDurationMillis),
            videoSoundEnabled = prefs.getBoolean(KEY_VIDEO_SOUND, defaults.videoSoundEnabled),
            pairPortraits = prefs.getBoolean(KEY_PAIRS, defaults.pairPortraits),
            freshnessWindowDays = prefs.getInt(KEY_FRESHNESS, defaults.freshnessWindowDays),
            showClock = prefs.getBoolean(KEY_CLOCK, defaults.showClock),
            pauseAutoResumeMillis = prefs.getLong(KEY_PAUSE_RESUME, defaults.pauseAutoResumeMillis),
            showDate = prefs.getBoolean(KEY_DATE, defaults.showDate),
            prefetchCount = prefs.getInt(KEY_PREFETCH, defaults.prefetchCount),
            // Перенос с 1.3: выбранная флешка становится хранилищем «по свободному
            // месту», иначе — память телевизора с объёмом из старых кэша и буфера.
            storageVolumeUuid = prefs.getString(KEY_STORAGE_VOLUME, null)
                ?: prefs.getString(KEY_OLD_EXTERNAL, defaults.storageVolumeUuid) ?: "",
            storageBytes = if (prefs.contains(KEY_STORAGE_BYTES)) prefs.getLong(KEY_STORAGE_BYTES, defaults.storageBytes)
            else maxOf(
                defaults.storageBytes,
                prefs.getLong(KEY_OLD_CACHE_BUDGET, 0L) + prefs.getLong(KEY_OLD_STREAM_BUFFER, 0L),
            ),
            storageByFree = if (prefs.contains(KEY_STORAGE_BY_FREE)) prefs.getBoolean(KEY_STORAGE_BY_FREE, false)
            else !prefs.getString(KEY_OLD_EXTERNAL, "").isNullOrBlank(),
            storageReserveBytes = prefs.getLong(
                KEY_STORAGE_RESERVE,
                prefs.getLong(KEY_OLD_EXTERNAL_RESERVE, defaults.storageReserveBytes),
            ),
            minStorePhotoBytes = prefs.getLong(KEY_MIN_STORE_PHOTO, defaults.minStorePhotoBytes),
            minStoreVideoBytes = prefs.getLong(KEY_MIN_STORE_VIDEO, defaults.minStoreVideoBytes),
            maxFileBytes = prefs.getLong(KEY_MAX_FILE, prefs.getLong(KEY_OLD_VIDEO_MAX_SIZE, defaults.maxFileBytes)),
            networkBps = prefs.getLong(KEY_NETWORK, defaults.networkBps),
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
            .putFloat(KEY_INSET_LANDSCAPE, value.frameInsetLandscape)
            .putFloat(KEY_INSET_PORTRAIT, value.frameInsetPortrait)
            .putFloat(KEY_MARGIN, value.edgeMargin)
            .putFloat(KEY_PLACEMENT, value.placementStrength)
            .putFloat(KEY_DIM, value.backgroundDim)
            .putInt(KEY_BLUR, value.blurSampleLongSide)
            .putFloat(KEY_MIN_PHOTO, value.minPhotoFraction)
            .putBoolean(KEY_TUNER, value.tunerEnabled)
            .putStringSet(KEY_FOLDERS, value.selectedFolders)
            .putBoolean(KEY_VIDEO, value.showVideo)
            .putBoolean(KEY_DOWNLOADS_DURING_VIDEO, value.downloadsDuringVideo)
            .putLong(KEY_VIDEO_MAX, value.videoMaxDurationMillis)
            .putBoolean(KEY_VIDEO_SOUND, value.videoSoundEnabled)
            .putBoolean(KEY_PAIRS, value.pairPortraits)
            .putInt(KEY_FRESHNESS, value.freshnessWindowDays)
            .putBoolean(KEY_CLOCK, value.showClock)
            .putLong(KEY_PAUSE_RESUME, value.pauseAutoResumeMillis)
            .putBoolean(KEY_DATE, value.showDate)
            .putInt(KEY_PREFETCH, value.prefetchCount)
            .putString(KEY_STORAGE_VOLUME, value.storageVolumeUuid)
            .putLong(KEY_STORAGE_BYTES, value.storageBytes)
            .putBoolean(KEY_STORAGE_BY_FREE, value.storageByFree)
            .putLong(KEY_STORAGE_RESERVE, value.storageReserveBytes)
            .putLong(KEY_MIN_STORE_PHOTO, value.minStorePhotoBytes)
            .putLong(KEY_MIN_STORE_VIDEO, value.minStoreVideoBytes)
            .putLong(KEY_MAX_FILE, value.maxFileBytes)
            .putLong(KEY_NETWORK, value.networkBps)
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
        const val KEY_INSET_LANDSCAPE = "frame_inset_landscape"
        const val KEY_INSET_PORTRAIT = "frame_inset_portrait"
        const val KEY_MARGIN = "edge_margin"
        const val KEY_PLACEMENT = "placement_strength"
        const val KEY_DIM = "background_dim"
        const val KEY_BLUR = "blur_sample_long_side"
        const val KEY_TUNER = "tuner_enabled"
        const val KEY_STORAGE_VOLUME = "storage_volume_uuid"
        const val KEY_STORAGE_BYTES = "storage_bytes"
        const val KEY_STORAGE_BY_FREE = "storage_by_free"
        const val KEY_STORAGE_RESERVE = "storage_reserve_bytes"
        const val KEY_MIN_STORE_PHOTO = "min_store_photo_bytes"
        const val KEY_MIN_STORE_VIDEO = "min_store_video_bytes"
        const val KEY_MAX_FILE = "max_file_bytes"
        const val KEY_NETWORK = "network_bps"
        /** Ключи до 1.4 — читаются один раз для переноса. */
        const val KEY_OLD_EXTERNAL = "external_storage_uuid"
        const val KEY_OLD_EXTERNAL_RESERVE = "external_reserve_bytes"
        const val KEY_OLD_CACHE_BUDGET = "cache_budget_bytes"
        const val KEY_OLD_STREAM_BUFFER = "stream_buffer_bytes"
        const val KEY_OLD_VIDEO_MAX_SIZE = "video_max_size_bytes"
        const val KEY_PREFETCH = "prefetch_count"
        const val KEY_REFRESH = "index_refresh_interval_millis"
        const val KEY_FOLDERS = "selected_folders"
        const val KEY_VIDEO = "show_video"
        const val KEY_DOWNLOADS_DURING_VIDEO = "downloads_during_video"
        const val KEY_VIDEO_MAX = "video_max_duration_millis"
        const val KEY_VIDEO_SOUND = "video_sound_enabled"
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
        frameInsetLandscape = frameInsetLandscape.coerceIn(0.3f, 1f),
        frameInsetPortrait = frameInsetPortrait.coerceIn(0.3f, 1f),
        edgeMargin = edgeMargin.coerceIn(0f, 0.25f),
        placementStrength = placementStrength.coerceIn(0f, 1f),
        backgroundDim = backgroundDim.coerceIn(0f, 1f),
        blurSampleLongSide = blurSampleLongSide.coerceIn(2, 64),
        // Выше двух третей отсеклись бы и полноразмерные снимки: копия с Диска
        // не больше 1280 px.
        minPhotoFraction = minPhotoFraction.coerceIn(0f, 0.6f),
        tunerEnabled = tunerEnabled,
        // Пустые строки в наборе сломали бы отбор: пустой префикс совпадает
        // со всем подряд, и «выбрано ничего» превратилось бы в «выбрано всё».
        selectedFolders = selectedFolders.filter { it.isNotBlank() }.toSet(),
        showVideo = showVideo,
        downloadsDuringVideo = downloadsDuringVideo,
        videoMaxDurationMillis = videoMaxDurationMillis.coerceIn(0L, 60L * 60 * 1000),
        videoSoundEnabled = videoSoundEnabled,
        pairPortraits = pairPortraits,
        freshnessWindowDays = freshnessWindowDays.coerceIn(1, 3650),
        showClock = showClock,
        pauseAutoResumeMillis = pauseAutoResumeMillis.coerceIn(0L, 24L * 60 * 60 * 1000),
        showDate = showDate,
        prefetchCount = prefetchCount.coerceIn(1, 50),
        storageVolumeUuid = storageVolumeUuid.trim(),
        storageBytes = storageBytes.coerceIn(0L, 4L * 1024 * 1024 * 1024 * 1024),
        storageByFree = storageByFree,
        storageReserveBytes = storageReserveBytes.coerceIn(0L, 1024L * 1024 * 1024 * 1024),
        minStorePhotoBytes = minStorePhotoBytes.coerceIn(0L, 1024L * 1024 * 1024),
        minStoreVideoBytes = minStoreVideoBytes.coerceIn(0L, 64L * 1024 * 1024 * 1024),
        maxFileBytes = maxFileBytes.coerceIn(0L, 64L * 1024 * 1024 * 1024),
        networkBps = networkBps.coerceIn(0L, 2_000_000_000L),
        // Не реже трёх часов: столько живут ссылки Диска на превью.
        indexRefreshIntervalMillis = indexRefreshIntervalMillis.coerceIn(
            60_000L,
            3L * 60 * 60 * 1000,
        ),
    )
}

/** Ниже этого порога кэшировать нечего: столько весит одна уменьшенная копия. */
