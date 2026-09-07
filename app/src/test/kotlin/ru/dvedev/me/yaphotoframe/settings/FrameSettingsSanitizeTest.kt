package ru.dvedev.me.yaphotoframe.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.dvedev.me.yaphotoframe.ui.FrameSettings

/**
 * Настройки приходят из веб-страницы, то есть снаружи и в любом виде.
 * Нормализация обязана выдержать что угодно, не бросив исключение: обработчик
 * запроса, упавший на середине, оставляет клиента без ответа, и это выглядит
 * как обрыв сети.
 */
class FrameSettingsSanitizeTest {

    @Test
    fun `отрицательные объёмы хранилища приводятся к нулю`() {
        val sanitized = FrameSettings(
            storageBytes = -1,
            storageReserveBytes = -5,
            minStorePhotoBytes = -1,
            minStoreVideoBytes = -1,
            maxFileBytes = -1,
            networkBps = -1,
        ).sanitized()

        assertEquals(0L, sanitized.storageBytes)
        assertEquals(0L, sanitized.storageReserveBytes)
        assertEquals(0L, sanitized.minStorePhotoBytes)
        assertEquals(0L, sanitized.minStoreVideoBytes)
        assertEquals(0L, sanitized.maxFileBytes)
        assertEquals(0L, sanitized.networkBps)
    }

    @Test
    fun `UUID тома обрезается по краям`() {
        assertEquals("AAAA-1111", FrameSettings(storageVolumeUuid = " AAAA-1111 ").sanitized().storageVolumeUuid)
    }

    @Test
    fun `нелепые значения приводятся к границам, а не отвергаются`() {
        val sanitized = FrameSettings(
            showDurationMillis = 1,
            driftAmplitude = 99f,
            frameInsetLandscape = -5f,
            blurSampleLongSide = 0,
            minPhotoFraction = 1f,
            prefetchCount = 10_000,
        ).sanitized()

        assertEquals(FrameSettings.MIN_SHOW_DURATION_MILLIS, sanitized.showDurationMillis)
        assertEquals(0.30f, sanitized.driftAmplitude, 1e-6f)
        assertEquals(0.3f, sanitized.frameInsetLandscape, 1e-6f)
        assertEquals(2, sanitized.blurSampleLongSide)
        assertEquals(0.6f, sanitized.minPhotoFraction, 1e-6f)
        assertEquals(50, sanitized.prefetchCount)
    }

    @Test
    fun `умолчания проходят нормализацию без изменений`() {
        assertEquals(FrameSettings(), FrameSettings().sanitized())
    }
}
