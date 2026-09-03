package ru.dvedev.me.yaphotoframe.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.dvedev.me.yaphotoframe.diag.Diary

/**
 * Настройка с ноутбука одной командой.
 *
 * Третий канал наравне со страницей в браузере и экраном на телевизоре — все
 * трое пишут в одно хранилище, поэтому не спорят друг с другом.
 *
 * Пример:
 * ```
 * adb shell am broadcast -a ru.dvedev.me.yaphotoframe.SET \
 *   -n ru.dvedev.me.yaphotoframe/.settings.SettingsReceiver \
 *   --es folderUrl https://disk.yandex.ru/d/XXXXXXXX --el showDurationMillis 30000
 * ```
 */
class SettingsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val store = SettingsStore(context)
        val touched = mutableListOf<String>()

        store.update { current ->
            var updated = current
            for (key in extras.keySet()) {
                val raw = extras.get(key)?.toString() ?: continue
                val applied = apply(updated, key, raw)
                if (applied != null) {
                    updated = applied
                    touched += "$key=$raw"
                }
            }
            updated
        }

        if (touched.isEmpty()) {
            Diary.problem("adb: не понял ни одной настройки из ${extras.keySet()}")
        } else {
            Diary.note("adb: применено ${touched.joinToString(", ")}")
        }
    }

    /** Возвращает null, если такой настройки нет или значение не разобралось. */
    private fun apply(settings: FrameSettingsAlias, key: String, raw: String): FrameSettingsAlias? =
        when (key) {
            "folderUrl" -> settings.copy(folderUrl = raw)
            "showDurationMillis" -> raw.toLongOrNull()?.let { settings.copy(showDurationMillis = it) }
            "crossfadeMillis" -> raw.toLongOrNull()?.let { settings.copy(crossfadeMillis = it) }
            "driftAmplitude" -> raw.toFloatOrNull()?.let { settings.copy(driftAmplitude = it) }
            "driftSpeedPerMinute" ->
                raw.toFloatOrNull()?.let { settings.copy(driftSpeedPerMinute = it) }
            "zoomAmount" -> raw.toFloatOrNull()?.let { settings.copy(zoomAmount = it) }
            "zoomSpeedPerMinute" ->
                raw.toFloatOrNull()?.let { settings.copy(zoomSpeedPerMinute = it) }
            "frameInset" -> raw.toFloatOrNull()?.let { settings.copy(frameInset = it) }
            "edgeMargin" -> raw.toFloatOrNull()?.let { settings.copy(edgeMargin = it) }
            "placementStrength" -> raw.toFloatOrNull()?.let { settings.copy(placementStrength = it) }
            "backgroundDim" -> raw.toFloatOrNull()?.let { settings.copy(backgroundDim = it) }
            "blurSampleLongSide" ->
                raw.toIntOrNull()?.let { settings.copy(blurSampleLongSide = it) }
            "tunerEnabled" -> raw.toBooleanStrictOrNull()?.let { settings.copy(tunerEnabled = it) }
            "cacheBudgetBytes" -> raw.toLongOrNull()?.let { settings.copy(cacheBudgetBytes = it) }
            "cacheItemThresholdBytes" ->
                raw.toLongOrNull()?.let { settings.copy(cacheItemThresholdBytes = it) }
            "prefetchCount" -> raw.toIntOrNull()?.let { settings.copy(prefetchCount = it) }
            "indexRefreshIntervalMillis" ->
                raw.toLongOrNull()?.let { settings.copy(indexRefreshIntervalMillis = it) }
            "showVideo" -> raw.toBooleanStrictOrNull()?.let { settings.copy(showVideo = it) }
            "videoMaxDurationMillis" ->
                raw.toLongOrNull()?.let { settings.copy(videoMaxDurationMillis = it) }
            "videoSoundEnabled" ->
                raw.toBooleanStrictOrNull()?.let { settings.copy(videoSoundEnabled = it) }
            "pairPortraits" ->
                raw.toBooleanStrictOrNull()?.let { settings.copy(pairPortraits = it) }
            "freshnessWindowDays" ->
                raw.toIntOrNull()?.let { settings.copy(freshnessWindowDays = it) }
            "minPhotoFraction" ->
                raw.toFloatOrNull()?.let { settings.copy(minPhotoFraction = it) }
            "showClock" -> raw.toBooleanStrictOrNull()?.let { settings.copy(showClock = it) }
            "showDate" -> raw.toBooleanStrictOrNull()?.let { settings.copy(showDate = it) }
            else -> null
        }
}

private typealias FrameSettingsAlias = ru.dvedev.me.yaphotoframe.ui.FrameSettings
