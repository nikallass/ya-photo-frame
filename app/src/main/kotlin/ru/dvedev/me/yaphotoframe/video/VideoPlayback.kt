package ru.dvedev.me.yaphotoframe.video

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import ru.dvedev.me.yaphotoframe.cache.Delivery

/**
 * Проигрывание ролика внутри заставки.
 *
 * Источник — либо файл из кэша, либо сеть: тяжёлую съёмку с фотоаппарата
 * скачивать целиком незачем, а хранилище отдаёт содержимое по диапазонам, что и
 * нужно для потока. Плееру всё равно, откуда, — разницу задаёт [Delivery].
 *
 * Звук по умолчанию выключен: заставка, внезапно заговорившая в тишине, пугает.
 */
class VideoPlayback(private val context: Context) {

    private var player: ExoPlayer? = null

    fun play(
        delivery: Delivery,
        surface: TextureView,
        soundEnabled: Boolean,
        onEnded: () -> Unit,
        onFailed: (Throwable) -> Unit,
        onSizeKnown: (Int, Int) -> Unit,
        onFirstFrame: () -> Unit,
    ) {
        stop()

        val uri = when (delivery) {
            is Delivery.Local -> Uri.fromFile(delivery.file)
            is Delivery.Streamed -> Uri.parse(delivery.url)
        }

        player = ExoPlayer.Builder(context).setLoadControl(loadControl()).build().apply {
            setVideoTextureView(surface)
            volume = if (soundEnabled) 1f else 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnded()
                }

                override fun onRenderedFirstFrame() {
                    onFirstFrame()
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    // Пропорции известны только после разбора файла — до этого
                    // поверхность занимает весь экран и растянула бы картинку.
                    onSizeKnown(size.width, size.height)
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Неподходящий кодек или оборванная сеть не должны застревать
                    // на экране: пусть рамка идёт дальше.
                    onFailed(error)
                }
            })
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    /**
     * Насколько плееру позволено буферизовать.
     *
     * Умолчания рассчитаны на телефон: плеер набирает до пятидесяти секунд
     * видео в память. Замерено на устройстве — куча вырастала до ста шестидесяти
     * мегабайт при потолке в сто девяносто два, то есть до падения оставался
     * шаг. Рамке столько незачем: ролик либо уже лежит на диске, либо тянется
     * по хорошему каналу, и запаса в десяток секунд с лихвой хватает.
     */
    private fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 4_000,
            /* maxBufferMs = */ 12_000,
            /* bufferForPlaybackMs = */ 1_000,
            /* bufferForPlaybackAfterRebufferMs = */ 2_000,
        )
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /** Сколько длится ролик; ноль — плеер ещё не выяснил. */
    fun durationMillis(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    fun stop() {
        player?.release()
        player = null
    }

    private companion object {
        /** Потолок буфера: на устройстве с двумя гигабайтами больше и не нужно. */
        const val TARGET_BUFFER_BYTES = 24 * 1024 * 1024
    }
}
