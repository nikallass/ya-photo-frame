package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.os.Handler
import android.os.Looper
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
        resetMotion()

        applyPlacement()
    }

    private fun resetMotion() {
        stopDrift()
        driftPausedAt = 0L
        translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
        pairHolder.translationX = 0f; pairHolder.translationY = 0f
        pairHolder.scaleX = 1f; pairHolder.scaleY = 1f
    }

    /** Сколько кадр уже уехал — сумма по обоим носителям движения. */
    private fun offsetX() = translationX + pairHolder.translationX
    private fun offsetY() = translationY + pairHolder.translationY
    private fun currentScale() = scaleX * pairHolder.scaleX

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

        // Приближение раздвигает кадр на половину прироста в каждую сторону —
        // столько же надо оставить до края.
        val zoom = settings.zoomAmount.coerceAtLeast(0f)
        val growX = (bounds.right - bounds.left) * zoom / 2
        val growY = (bounds.bottom - bounds.top) * zoom / 2
        val roomX = (if (direction.first > 0) width - bounds.right else bounds.left) - growX
        val roomY = (if (direction.second > 0) height - bounds.bottom else bounds.top) - growY

        val wanted = width * settings.driftAmplitude
        val travelX = min(wanted, roomX.coerceAtLeast(0f))
        val travelY = min(wanted, roomY.coerceAtLeast(0f))

        val speed = settings.driftSpeedPerMinute.coerceAtLeast(MIN_DRIFT_SPEED)
        val distance = maxOf(travelX, travelY) / width
        val driftMillis = (distance / speed * MILLIS_PER_MINUTE).toLong()
            .coerceIn(MIN_DRIFT_MILLIS, durationMillis)
        val zoomSpeed = settings.zoomSpeedPerMinute.coerceAtLeast(MIN_DRIFT_SPEED)
        val zoomMillis = (zoom / zoomSpeed * MILLIS_PER_MINUTE).toLong()
            .coerceIn(MIN_DRIFT_MILLIS, durationMillis)

        val startX = if (fromCurrentPosition) offsetX() else 0f
        val startY = if (fromCurrentPosition) offsetY() else 0f
        val startScale = if (fromCurrentPosition) currentScale() else 1f
        stopDrift()
        // Растёт кадр вокруг своей середины, а не вокруг угла экрана.
        val pivotX = (bounds.left + bounds.right) / 2f
        val pivotY = (bounds.top + bounds.bottom) / 2f
        pairHolder.pivotX = pivotX; pairHolder.pivotY = pivotY
        this.pivotX = pivotX; this.pivotY = pivotY
        drift = Drift(
            startedAt = System.currentTimeMillis(),
            durationMillis = driftMillis,
            fromX = startX,
            fromY = startY,
            toX = travelX * direction.first,
            toY = travelY * direction.second,
            zoomMillis = zoomMillis,
            fromScale = startScale,
            toScale = 1f + zoom,
        )
        driftTick.run()
    }

    private class Drift(
        val startedAt: Long,
        val durationMillis: Long,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val zoomMillis: Long,
        val fromScale: Float,
        val toScale: Float,
    )

    private var drift: Drift? = null
    private val driftHandler = Handler(Looper.getMainLooper())

    /**
     * Шаг хода.
     *
     * Аниматор гнал бы перерисовку всего экрана шестьдесят раз в секунду ради
     * сдвига в пиксель за секунду — телевизор не успевал, и растворение при
     * смене кадра дёргалось. Дюжины шагов в секунду хватает: шаг выходит меньше
     * пикселя, а фильтрация при отрисовке сглаживает и его.
     */
    private val driftTick = object : Runnable {
        override fun run() {
            val current = drift ?: return
            val elapsed = (System.currentTimeMillis() - current.startedAt).toFloat()
            val progress = (elapsed / current.durationMillis).coerceIn(0f, 1f)
            val zoomProgress = (elapsed / current.zoomMillis).coerceIn(0f, 1f)
            val x = current.fromX + (current.toX - current.fromX) * progress
            val y = current.fromY + (current.toY - current.fromY) * progress
            val scale = current.fromScale + (current.toScale - current.fromScale) * zoomProgress

            // Пока слой лежит в аппаратном буфере (идёт растворение), двигаем и
            // растим слой целиком: это бесплатно, буфер не перерисовывается.
            // Фон при этом уезжает на те же пиксели — на размытом это не видно.
            // Потом движение переносится на обойму со снимком, а слой остаётся
            // там, где его застал конец растворения: возврат на место был бы
            // виден как рывок фона, и на мелких снимках его замечали.
            if (layerType == LAYER_TYPE_HARDWARE) {
                pairHolder.translationX = 0f; pairHolder.translationY = 0f
                pairHolder.scaleX = 1f; pairHolder.scaleY = 1f
                translationX = x; translationY = y; scaleX = scale; scaleY = scale
            } else {
                pairHolder.translationX = x - translationX
                pairHolder.translationY = y - translationY
                pairHolder.scaleX = scale / scaleX
                pairHolder.scaleY = scale / scaleY
            }
            if (progress < 1f || zoomProgress < 1f) {
                driftHandler.postDelayed(this, DRIFT_TICK_MILLIS)
            } else {
                drift = null
            }
        }
    }

    /** Замирает на месте — например, на время растворения, чтобы слой не перерисовывался. */
    fun stopDrift() {
        drift = null
        driftHandler.removeCallbacks(driftTick)
    }

    private var driftPausedAt = 0L

    /** Ход замирает на месте; при снятии паузы продолжается оттуда же. */
    fun setDriftPaused(paused: Boolean) {
        val current = drift ?: return
        if (paused && driftPausedAt == 0L) {
            driftPausedAt = System.currentTimeMillis()
            driftHandler.removeCallbacks(driftTick)
        } else if (!paused && driftPausedAt != 0L) {
            val pausedFor = System.currentTimeMillis() - driftPausedAt
            driftPausedAt = 0L
            drift = Drift(
                startedAt = current.startedAt + pausedFor,
                durationMillis = current.durationMillis,
                fromX = current.fromX, fromY = current.fromY,
                toX = current.toX, toY = current.toY,
                zoomMillis = current.zoomMillis,
                fromScale = current.fromScale, toScale = current.toScale,
            )
            driftHandler.post(driftTick)
        }
    }

    override fun onDetachedFromWindow() {
        stopDrift()
        super.onDetachedFromWindow()
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
        resetMotion()
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
        /** Около двенадцати шагов в секунду. */
        const val DRIFT_TICK_MILLIS = 80L

        const val MILLIS_PER_MINUTE = 60_000f
        const val MIN_DRIFT_SPEED = 0.0005f
        const val MIN_DRIFT_MILLIS = 200L

        /** Просвет между снимками в паре, долей от ширины экрана. */
        const val PAIR_GAP = 0.015f
    }
}
