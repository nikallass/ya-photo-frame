package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Один кадр со своим фоном — слой, который можно показать или спрятать целиком.
 *
 * Собран из обычных `ImageView`, а не рисуется вручную, ровно ради дрейфа:
 * анимация `translationX`/`translationY` идёт на RenderThread и ничего не стоит
 * главному потоку. Своя отрисовка заставляла бы перерисовывать список команд
 * каждый кадр все шестьдесят секунд показа.
 *
 * Умеет показать и пару вертикальных снимков рядом: поодиночке такой кадр
 * занимает треть ширины экрана, а вдвоём они заполняют его целиком.
 */
class FrameLayer(context: Context) : FrameLayout(context) {

    private val backgroundView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val scrimView = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val frameView = imageView()
    private val companionView = imageView()
    private val pairHolder = FrameLayout(context)
    private val dateView = CaptureDate.label(context)
    private val companionDateView = CaptureDate.label(context)

    private var frameBitmap: Bitmap? = null
    private var companionBitmap: Bitmap? = null
    private var backgroundBitmap: Bitmap? = null
    private var placement: FramePlacement = FramePlacement.CENTER
    private var settings: FrameSettings = FrameSettings()

    private fun imageView() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        adjustViewBounds = false
    }

    init {
        addView(backgroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pairHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pairHolder.addView(frameView, LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        pairHolder.addView(companionView, LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        // Подписи внутри той же обоймы, что и снимки: дрейфуют вместе с ними,
        // иначе дата отставала бы от своего кадра.
        pairHolder.addView(dateView, LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        pairHolder.addView(companionDateView, LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        setBackgroundColor(Color.BLACK)
    }

    fun setContent(
        frame: Bitmap,
        background: Bitmap,
        companion: Bitmap?,
        placement: FramePlacement,
        settings: FrameSettings,
        dateText: String?,
        companionDateText: String?,
    ) {
        recycleBitmaps()
        frameBitmap = frame
        companionBitmap = companion
        backgroundBitmap = background
        this.placement = placement
        this.settings = settings

        backgroundView.setImageBitmap(background)
        scrimView.alpha = settings.backgroundDim
        frameView.setImageBitmap(frame)
        companionView.setImageBitmap(companion)
        companionView.visibility = if (companion == null) GONE else VISIBLE
        dateView.text = dateText.orEmpty()
        dateView.visibility = if (dateText == null) GONE else VISIBLE
        companionDateView.text = companionDateText.orEmpty()
        companionDateView.visibility =
            if (companion == null || companionDateText == null) GONE else VISIBLE
        pairHolder.translationX = 0f
        pairHolder.translationY = 0f

        applyPlacement()
    }

    /**
     * Пускает кадр в медленный дрейф.
     *
     * Ход не симметричный: кадр стартует там, где его поставило размещение, и
     * уезжает в ту сторону, где до края дальше. Симметричный размах требовал бы
     * свободного места с обеих сторон, а смещение по золотому сечению одну
     * сторону как раз поджимает — в итоге заданная длина обрезалась вдвое.
     *
     * Длина и скорость заданы порознь, поэтому время выводится из них, а не из
     * длительности показа. Когда скорость велика, ход завершается раньше конца
     * показа и кадр замирает; когда мала — не доходит до конца. И то и другое
     * законно, это и есть две разные ручки.
     */
    fun startDrift(durationMillis: Long, fromCurrentPosition: Boolean = false) {
        val bounds = contentBounds() ?: return
        val direction = placement.driftDirection()

        val roomX = if (direction.first > 0) width - bounds.right else bounds.left
        val roomY = if (direction.second > 0) height - bounds.bottom else bounds.top

        val wanted = width * settings.driftAmplitude
        val travelX = min(wanted, roomX.toFloat().coerceAtLeast(0f))
        val travelY = min(wanted, roomY.toFloat().coerceAtLeast(0f))
        if (travelX <= 1f && travelY <= 1f) return

        val speed = settings.driftSpeedPerMinute.coerceAtLeast(MIN_DRIFT_SPEED)
        val distance = maxOf(travelX, travelY) / width
        val driftMillis = (distance / speed * MILLIS_PER_MINUTE).toLong()
            .coerceIn(MIN_DRIFT_MILLIS, durationMillis)

        val startX = if (fromCurrentPosition) pairHolder.translationX else 0f
        val startY = if (fromCurrentPosition) pairHolder.translationY else 0f
        pairHolder.animate().cancel()
        pairHolder.translationX = startX
        pairHolder.translationY = startY
        pairHolder.animate()
            .translationX(travelX * direction.first)
            .translationY(travelY * direction.second)
            .setDuration(driftMillis)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    /**
     * Применяет новые настройки к уже показанному кадру.
     *
     * Дрейф не сбрасывается в начало, а продолжается из текущего положения к
     * новой цели: рывок назад посреди показа выглядел бы поломкой, а при подборе
     * ползунком такие рывки шли бы один за другим.
     */
    fun applySettings(settings: FrameSettings, showDurationMillis: Long) {
        this.settings = settings
        scrimView.alpha = settings.backgroundDim
        applyPlacement()
        if (frameBitmap != null) startDrift(showDurationMillis, fromCurrentPosition = true)
    }

    fun clear() {
        pairHolder.animate().cancel()
        frameView.setImageDrawable(null)
        companionView.setImageDrawable(null)
        backgroundView.setImageDrawable(null)
        recycleBitmaps()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPlacement()
    }

    private class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun contentBounds(): Bounds? {
        val first = frameView.layoutParams as LayoutParams
        if (first.width == 0) return null
        val second = companionView.layoutParams as LayoutParams
        val right = if (companionBitmap == null) first.leftMargin + first.width
        else second.leftMargin + second.width
        return Bounds(
            left = first.leftMargin,
            top = first.topMargin,
            right = right,
            bottom = first.topMargin + first.height,
        )
    }

    private fun applyPlacement() {
        val frame = frameBitmap ?: return
        if (width == 0 || height == 0) return

        val companion = companionBitmap
        val gap = if (companion == null) 0 else (width * PAIR_GAP).roundToInt()
        val naturalWidth = frame.width + (companion?.width ?: 0) + gap
        val naturalHeight = maxOf(frame.height, companion?.height ?: 0)

        // Уменьшаем, только если не помещается. Увеличивать нельзя никогда:
        // хранилище отдаёт максимум 1280 по длинной стороне, и растягивание до
        // ширины экрана дало бы мыло.
        val scale = min(
            1f,
            min(
                width * settings.frameInset / naturalWidth,
                height * settings.frameInset / naturalHeight,
            ),
        )
        val drawnWidth = (naturalWidth * scale).roundToInt()
        val drawnHeight = (naturalHeight * scale).roundToInt()
        val left = (centerAlong(placement.horizontal, width, drawnWidth) - drawnWidth / 2f)
            .roundToInt()
        val top = (centerAlong(placement.vertical, height, drawnHeight) - drawnHeight / 2f)
            .roundToInt()

        val firstWidth = (frame.width * scale).roundToInt()
        val firstHeight = (frame.height * scale).roundToInt()
        place(frameView, left, top, firstWidth, firstHeight, drawnHeight)
        placeDate(dateView, frameView)

        if (companion != null) {
            val secondLeft = left + firstWidth + (gap * scale).roundToInt()
            place(
                companionView,
                secondLeft,
                top,
                (companion.width * scale).roundToInt(),
                (companion.height * scale).roundToInt(),
                drawnHeight,
            )
            placeDate(companionDateView, companionView)
        }
    }

    /** Подпись прижимается к нижнему правому углу своего снимка. */
    private fun placeDate(label: android.widget.TextView, image: ImageView) {
        label.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val image1 = image.layoutParams as LayoutParams
        val params = label.layoutParams as LayoutParams
        params.width = label.measuredWidth
        params.height = label.measuredHeight
        params.leftMargin = image1.leftMargin + image1.width - label.measuredWidth
        params.topMargin = image1.topMargin + image1.height - label.measuredHeight
        label.layoutParams = params
    }

    /** Снимки в паре бывают разной высоты — выравниваем их по середине. */
    private fun place(view: ImageView, left: Int, top: Int, width: Int, height: Int, rowHeight: Int) {
        val params = view.layoutParams as LayoutParams
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top + (rowHeight - height) / 2
        view.layoutParams = params
    }

    /**
     * Куда попадёт середина кадра вдоль одной оси.
     *
     * Точка золотого сечения — лишь пожелание: кадр прижимается обратно, чтобы
     * между ним и краем остался отступ. Когда свободного места нет вовсе, оба
     * ограничения сходятся в середину, и кадр встаёт по центру.
     */
    private fun centerAlong(bias: Float, available: Int, drawn: Int): Float {
        val margin = available * settings.edgeMargin
        val minCenter = margin + drawn / 2f
        val maxCenter = available - margin - drawn / 2f
        val middle = available / 2f
        val desired = middle + (bias - 0.5f) * available * settings.placementStrength
        return if (minCenter > maxCenter) middle else desired.coerceIn(minCenter, maxCenter)
    }

    private fun recycleBitmaps() {
        frameBitmap?.recycle()
        companionBitmap?.recycle()
        backgroundBitmap?.recycle()
        frameBitmap = null
        companionBitmap = null
        backgroundBitmap = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000f
        const val MIN_DRIFT_SPEED = 0.0005f
        const val MIN_DRIFT_MILLIS = 200L

        /** Просвет между снимками в паре, долей от ширины экрана. */
        const val PAIR_GAP = 0.015f
    }
}
