package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.os.SystemClock
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import ru.dvedev.me.yaphotoframe.slideshow.PreparedItem
import ru.dvedev.me.yaphotoframe.slideshow.PreparedPhoto
import ru.dvedev.me.yaphotoframe.slideshow.PreparedVideo

/**
 * Экран рамки: слои, которые сменяют друг друга.
 *
 * Слоёв для фотографий ровно два, и они переиспользуются по кругу — новый кадр
 * готовится на спрятанном и проявляется поверх видимого. Битмапы предыдущего
 * освобождаются только после того, как переход завершится: освободить их раньше
 * значило бы показать пустоту в середине перехода.
 *
 * Ролику отведён отдельный слой: поверхность для видео одна на всё приложение,
 * переиспользовать её как обычную картинку нельзя.
 */
class SlideshowView(context: Context) : FrameLayout(context) {

    private val photoLayers = listOf(FrameLayer(context), FrameLayer(context))
    private val videoLayer = VideoLayer(context)
    private val overlay = FrameOverlay(context)

    private var visible: View? = null
    private var nextPhotoIndex = 0

    /**
     * Что сделать, когда слой с роликом убран с экрана.
     *
     * Плеер отпускают не тогда, когда ролик кончился, а когда его слой уже
     * скрылся: отпущенный плеер гасит поверхность в чёрный, и вместо
     * замирающего последнего кадра посреди перехода возникал чёрный
     * прямоугольник.
     */
    var onVideoLayerReleased: (() -> Unit)? = null

    /** Куда плеер выводит картинку. */
    val videoSurface get() = videoLayer.surface

    /** Плеер отрисовал первый кадр — постер можно убирать. */
    fun hideVideoPoster() = videoLayer.hidePoster()

    /** Вписывает картинку ролика в экран, сохраняя пропорции. */
    fun fitVideo(width: Int, height: Int, insetFraction: Float) =
        videoLayer.fitVideo(width, height, insetFraction)

    init {
        setBackgroundColor(Color.BLACK)
        (photoLayers + videoLayer).forEach {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            it.alpha = 0f
        }
        addView(overlay)
    }

    fun show(
        prepared: PreparedItem,
        key: String,
        settings: FrameSettings,
        animate: Boolean,
    ) {
        val incoming: View = when (prepared) {
            is PreparedPhoto -> photoLayers[nextPhotoIndex].also {
                nextPhotoIndex = 1 - nextPhotoIndex
                it.setContent(
                    frame = prepared.frame,
                    background = prepared.background,
                    companion = prepared.companion,
                    key = key,
                    settings = settings,
                    dateText = dateFor(settings, prepared.item.takenAtMillis),
                    companionDateText = dateFor(settings, prepared.companionItem?.takenAtMillis),
                )
            }

            is PreparedVideo -> videoLayer.also {
                it.setContent(
                    background = prepared.background,
                    poster = prepared.poster,
                    settings = settings,
                    dateText = dateFor(settings, prepared.item.takenAtMillis),
                )
            }
        }

        val outgoing = visible
        visible = incoming
        incoming.bringToFront()
        overlay.bringToFront()
        overlay.apply(settings)

        val duration = if (animate) settings.crossfadeMillis else 0L
        // Уходящий слой не гасим: он непрозрачный и лежит под приходящим, а
        // смесь «верх·α + низ·(1−α)» получается одна и та же, гаснет низ или
        // нет. Зато экран смешивает один слой, а не два. Ход его тоже
        // останавливаем: сдвиг под приходящим не виден, а перерисовку вызывает.
        if (outgoing !== incoming && outgoing is FrameLayer) outgoing.stopDrift()
        startFade(incoming, duration) {
            if (outgoing !== incoming) release(outgoing)
        }
        // Ход идёт с первого кадра растворения: когда он стартовал по его
        // окончании, последние доли секунды перехода кадр стоял, и это
        // читалось как заминка.
        if (incoming is FrameLayer) {
            incoming.startDrift(settings.showDurationMillis + settings.crossfadeMillis)
            // Долистали на паузе — новый кадр тоже стоит, а не плывёт.
            if (paused) incoming.setDriftPaused(true)
        }
    }

    private var paused = false

    private var fade: Runnable? = null
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeCurve = AccelerateDecelerateInterpolator()

    /**
     * Проявляет слой сам, тридцать шагов в секунду.
     *
     * Системный аниматор просит кадр на каждый vsync, а телевизор смешивает
     * два полноэкранных слоя дольше шестнадцати миллисекунд — половина
     * кадров опаздывала, и растворение шло рывками. При тридцати шагах в
     * секунду у каждого кадра вдвое больше времени, и он успевает всегда:
     * ровные тридцать выглядят лучше дёрганых шестидесяти.
     *
     * Слой на время растворения кладётся в аппаратный буфер: без этого
     * контейнер с несколькими детьми при смене прозрачности рисовался бы в
     * отдельный буфер на каждом шаге.
     */
    private fun startFade(view: View, durationMillis: Long, onEnd: () -> Unit) {
        cancelFade()
        view.animate().cancel()
        if (durationMillis <= 0L) {
            view.alpha = 1f
            onEnd()
            return
        }
        // Едва видимый, а не нулевой: слой с нулевой прозрачностью не рисуется,
        // и буфер под него не строится — тогда первый шаг растворения строил
        // бы его сам и опаздывал.
        view.alpha = 1f / 255
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val startedAt = SystemClock.elapsedRealtime() + FADE_WARMUP_MILLIS
        val tick = object : Runnable {
            override fun run() {
                val progress = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / durationMillis)
                    .coerceIn(0f, 1f)
                view.alpha = maxOf(fadeCurve.getInterpolation(progress), 1f / 255)
                if (progress < 1f) {
                    fadeHandler.postDelayed(this, FADE_TICK_MILLIS)
                } else {
                    fade = null
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    onEnd()
                }
            }
        }
        fade = tick
        // Первый шаг — после того, как буфер построен на кадре без движения.
        fadeHandler.postDelayed(tick, FADE_WARMUP_MILLIS)
    }

    private fun cancelFade() {
        fade?.let { fadeHandler.removeCallbacks(it) }
        fade = null
    }

    fun setSound(on: Boolean) = overlay.setSound(on)

    fun flashSound(enabled: Boolean) = overlay.flashSound(enabled)

    /** Пауза: ход замирает, значок в углу; часы идут своим чередом. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        photoLayers.forEach { it.setDriftPaused(paused) }
        overlay.setPaused(paused)
    }

    /** Раздаёт новые настройки слоям: видимый обновится прямо сейчас. */
    fun applySettings(settings: FrameSettings) {
        photoLayers.forEach { it.applySettings(settings, settings.showDurationMillis) }
        videoLayer.applySettings(settings)
        // Часы тоже: иначе включённые ползунком они появлялись бы только со
        // следующим кадром, то есть через минуту, и выглядело бы это как
        // «настройка не работает».
        overlay.apply(settings)
    }

    fun clear() {
        cancelFade()
        photoLayers.forEach {
            it.animate().cancel()
            it.alpha = 0f
            it.clear()
        }
        videoLayer.animate().cancel()
        videoLayer.alpha = 0f
        videoLayer.clear()
        overlay.stop()
        visible = null
    }

    /** Убрал слой ролика с экрана — теперь можно отпускать плеер. */
    private companion object {
        /** Тридцать шагов в секунду. */
        const val FADE_TICK_MILLIS = 33L

        /** Пара кадров на постройку буфера слоя, прежде чем он начнёт проявляться. */
        const val FADE_WARMUP_MILLIS = 40L
    }

    private fun release(view: View?) {
        when (view) {
            is FrameLayer -> view.clear()
            is VideoLayer -> {
                onVideoLayerReleased?.invoke()
                view.clear()
            }
        }
    }

    private fun dateFor(settings: FrameSettings, takenAtMillis: Long?): String? =
        if (settings.showDate) CaptureDate.format(takenAtMillis) else null
}
