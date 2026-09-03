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

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    fun play(
        delivery: Delivery,
        surface: TextureView,
        soundEnabled: Boolean,
        onEnded: () -> Unit,
        onFailed: (Throwable) -> Unit,
        onSizeKnown: (Int, Int) -> Unit,
        onFirstFrame: () -> Unit,
        /** Ролик встал на подкачку уже после старта — владелец это видит как заикание. */
        onStalled: () -> Unit = {},
    ) {
        stop()
        var started = false

        val uri = when (delivery) {
            is Delivery.Local -> Uri.fromFile(delivery.file)
            is Delivery.Streamed -> Uri.parse(delivery.url)
        }

        val streamed = delivery is Delivery.Streamed
        player = ExoPlayer.Builder(context).setLoadControl(loadControl(streamed)).build().apply {
            setVideoTextureView(surface)
            volume = if (soundEnabled) 1f else 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnded()
                    if (state == Player.STATE_BUFFERING && started) onStalled()
                }

                override fun onRenderedFirstFrame() {
                    started = true
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
    private fun loadControl(streamed: Boolean): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ if (streamed) 15_000 else 4_000,
            /* maxBufferMs = */ if (streamed) 45_000 else 12_000,
            /* bufferForPlaybackMs = */ if (streamed) 3_000 else 1_000,
            // Потоку после остановки — набрать побольше: частые короткие
            // остановки раздражают сильнее одной длинной.
            /* bufferForPlaybackAfterRebufferMs = */ if (streamed) 6_000 else 2_000,
        )
        .setTargetBufferBytes(if (streamed) STREAM_BUFFER_BYTES else TARGET_BUFFER_BYTES)
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

        /** Потоку с Диска запас нужнее, но куча всего 192 МБ. */
        const val STREAM_BUFFER_BYTES = 40 * 1024 * 1024
    }
}
