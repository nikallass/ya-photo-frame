package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Color
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
        placement: FramePlacement,
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
                    placement = placement,
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
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (outgoing !== incoming) release(outgoing) }
            .start()

        if (outgoing !== incoming) {
            outgoing?.animate()?.cancel()
            outgoing?.animate()
                ?.alpha(0f)
                ?.setDuration(duration)
                ?.setInterpolator(AccelerateDecelerateInterpolator())
                ?.start()
        }

        if (incoming is FrameLayer) {
            incoming.startDrift(settings.showDurationMillis + settings.crossfadeMillis)
        }
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
