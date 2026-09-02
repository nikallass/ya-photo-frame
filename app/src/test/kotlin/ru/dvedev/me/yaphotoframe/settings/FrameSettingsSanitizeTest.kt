package ru.dvedev.me.yaphotoframe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dvedev.me.yaphotoframe.cache.CachePolicy
import ru.dvedev.me.yaphotoframe.ui.FrameSettings

/**
 * Настройки приходят из веб-страницы, то есть снаружи и в любом виде.
 * Нормализация обязана выдержать что угодно, не бросив исключение: обработчик
 * запроса, упавший на середине, оставляет клиента без ответа, и это выглядит
 * как обрыв сети.
 */
class FrameSettingsSanitizeTest {

    @Test
    fun `бюджет кэша меньше мегабайта не роняет нормализацию`() {
        val sanitized = FrameSettings(cacheBudgetBytes = 300 * 1024).sanitized()

        assertEquals(CachePolicy.MIN_BUDGET_BYTES, sanitized.cacheBudgetBytes)
        assertTrue(
            "порог не может быть больше бюджета",
            sanitized.cacheItemThresholdBytes <= sanitized.cacheBudgetBytes,
        )
    }

    @Test
    fun `порог кэширования прижимается к нормализованному бюджету`() {
        val sanitized = FrameSettings(
            cacheBudgetBytes = 100L * 1024 * 1024,
            cacheItemThresholdBytes = 900L * 1024 * 1024,
        ).sanitized()

        assertEquals(100L * 1024 * 1024, sanitized.cacheItemThresholdBytes)
    }

    @Test
    fun `нелепые значения приводятся к границам, а не отвергаются`() {
        val sanitized = FrameSettings(
            showDurationMillis = 1,
            driftAmplitude = 99f,
            frameInset = -5f,
            blurSampleLongSide = 0,
            prefetchCount = 10_000,
        ).sanitized()

        assertEquals(FrameSettings.MIN_SHOW_DURATION_MILLIS, sanitized.showDurationMillis)
        assertEquals(0.30f, sanitized.driftAmplitude, 1e-6f)
        assertEquals(0.3f, sanitized.frameInset, 1e-6f)
        assertEquals(2, sanitized.blurSampleLongSide)
        assertEquals(50, sanitized.prefetchCount)
    }
}
