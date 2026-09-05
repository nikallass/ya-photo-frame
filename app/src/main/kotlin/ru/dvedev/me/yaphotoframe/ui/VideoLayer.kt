package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Слой с роликом: то же устройство, что у слоя с фотографией, но вместо
 * снимка — поверхность для видео.
 *
 * Поверх поверхности лежит постер — кадр из того же ролика. Он закрывает собой
 * то мгновение, пока плеер разбирает файл: без него на экране возникал чёрный
 * прямоугольник, и смена снимка на ролик выглядела как поломка. Убирается он
 * тогда, когда плеер отрисует первый настоящий кадр.
 *
 * В конце ролика плеер не отпускают, пока слой не скроется: поверхность держит
 * последний кадр, и переход к следующему снимку идёт от него, а не от черноты.
 */
class VideoLayer(context: Context) : FrameLayout(context) {

    private val backgroundView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val scrimView = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val posterView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val dateView = CaptureDate.label(context)

    /**
     * Именно TextureView, а не SurfaceView.
     *
     * SurfaceView — дыра в окне, поверх которой ничего не рисуется и которая не
     * умеет затухать: alpha на неё не действует. При смене кадра слой честно
     * фейдился, а видео в нём — нет: висело до конца перехода и исчезало
     * рывком, на миг показывая чёрную подложку. TextureView живёт в общем
     * дереве, затухает как любой View и держит последний кадр после конца
     * ролика. Цена — лишний буфер на экран; на 1080p это восемь мегабайт.
     */
    val surface = TextureView(context)

    private var backgroundBitmap: Bitmap? = null
    private var posterBitmap: Bitmap? = null

    init {
        addView(backgroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            surface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        addView(
            posterView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        addView(dateView, LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        setBackgroundColor(Color.BLACK)
    }

    fun setContent(
        background: Bitmap,
        poster: Bitmap,
        settings: FrameSettings,
        dateText: String?,
    ) {
        backgroundBitmap?.recycle()
        posterBitmap?.recycle()
        backgroundBitmap = background
        posterBitmap = poster

        backgroundView.setImageBitmap(background)
        posterView.setImageBitmap(poster)
        posterView.alpha = 1f
        scrimView.alpha = settings.backgroundDim

        dateView.text = dateText.orEmpty()
        dateView.visibility = if (dateText == null) GONE else VISIBLE

        // До того как плеер сообщит размер, постер занимает то же место, что
        // займёт картинка: он и показывает пропорции ролика.
        fitVideo(poster.width, poster.height, settings.insetFor(poster.width, poster.height))
    }

    /** Плеер отрисовал первый кадр — постер больше не нужен. */
    fun hidePoster() {
        posterView.animate().cancel()
        posterView.animate().alpha(0f).setDuration(POSTER_FADE_MILLIS).start()
    }

    /**
     * Подгоняет поверхность под пропорции ролика.
     *
     * Плеер выводит картинку ровно в ту поверхность, что ему дали, и ничего не
     * вписывает: поверхность во весь экран растягивала бы вертикальную съёмку
     * поперёк. Размер приходит от плеера, когда тот разберёт файл.
     */
    fun fitVideo(videoWidth: Int, videoHeight: Int, insetFraction: Float) {
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return

        val scale = minOf(
            width * insetFraction / videoWidth,
            height * insetFraction / videoHeight,
        )
        val drawnWidth = (videoWidth * scale).roundToInt()
        val drawnHeight = (videoHeight * scale).roundToInt()

        size(surface, drawnWidth, drawnHeight)
        size(posterView, drawnWidth, drawnHeight)
        placeDate(drawnWidth, drawnHeight)
    }

    fun applySettings(settings: FrameSettings) {
        scrimView.alpha = settings.backgroundDim
    }

    fun clear() {
        posterView.animate().cancel()
        backgroundView.setImageDrawable(null)
        posterView.setImageDrawable(null)
        backgroundBitmap?.recycle()
        posterBitmap?.recycle()
        backgroundBitmap = null
        posterBitmap = null
    }

    private fun size(view: View, width: Int, height: Int) {
        val params = view.layoutParams as LayoutParams
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        view.layoutParams = params
    }

    private fun placeDate(videoWidth: Int, videoHeight: Int) {
        dateView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val params = dateView.layoutParams as LayoutParams
        params.width = dateView.measuredWidth
        params.height = dateView.measuredHeight
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = (width + videoWidth) / 2 - dateView.measuredWidth
        params.topMargin = (height + videoHeight) / 2 - dateView.measuredHeight
        dateView.layoutParams = params
    }

    private companion object {
        const val POSTER_FADE_MILLIS = 350L
    }
}
