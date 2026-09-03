package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Часы поверх кадра.
 *
 * Живут отдельным слоем над всеми остальными и не участвуют в переходах: часы,
 * мигающие вместе со сменой снимка, выглядели бы поломкой.
 *
 * Дата съёмки сюда не входит намеренно — она принадлежит снимку, а не экрану,
 * и живёт в его углу. Рядом с часами её принимали за сегодняшнее число.
 *
 * Часы не стоят на месте: неподвижная светлая надпись за годы выжигает пиксели.
 * Они медленно плавают внутри угла и время от времени переезжают в другой угол,
 * поровну деля между ними время.
 */
class FrameOverlay(context: Context) : FrameLayout(context) {

    private val clockView = TextView(context).apply {
        textSize = 44f
        setTextColor(Color.WHITE)
        alpha = 0.85f
        setShadowLayer(12f, 0f, 2f, Color.BLACK)
    }

    private val pauseView = TextView(context).apply {
        text = "❚❚"
        textSize = 22f
        setTextColor(Color.WHITE)
        alpha = 0.6f
        setShadowLayer(10f, 0f, 2f, Color.BLACK)
        visibility = GONE
    }

    /** Значок паузы в нижнем углу — иначе непонятно, почему кадр не меняется. */
    fun setPaused(paused: Boolean) {
        pauseView.visibility = if (paused) VISIBLE else GONE
    }

    private val soundView = SpeakerIcon(context).apply {
        alpha = 0.8f
        visibility = GONE
    }

    /** Ролик идёт со звуком — динамик горит всё это время; снимок — гаснет сразу. */
    private var soundOn = false

    private val endFlash = Runnable { showSound() }

    fun setSound(on: Boolean) {
        soundOn = on
        handler.removeCallbacks(endFlash)
        showSound()
    }

    /**
     * Звук переключили с пульта: на три секунды динамик, включённый или
     * перечёркнутый, — иначе нажатие вслепую ничем не подтверждается. Потом
     * значок возвращается к тому, что ролик и звук диктуют сами.
     */
    fun flashSound(enabled: Boolean) {
        handler.removeCallbacks(endFlash)
        soundView.muted = !enabled
        soundView.visibility = VISIBLE
        handler.postDelayed(endFlash, SOUND_FLASH_MILLIS)
    }

    private fun showSound() {
        soundView.muted = false
        soundView.visibility = if (soundOn) VISIBLE else GONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMillis = System.currentTimeMillis()
    private var atRightCorner = true

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            drift()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    init {
        addView(
            clockView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        addView(
            pauseView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { leftMargin = MARGIN; bottomMargin = MARGIN },
        )
        // Рядом с паузой, в том же углу: правее неё, чтобы не наезжать.
        addView(
            soundView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { leftMargin = MARGIN + SOUND_OFFSET; bottomMargin = MARGIN - 12 },
        )
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    fun apply(settings: FrameSettings) {
        val show = settings.showClock
        clockView.visibility = if (show) VISIBLE else GONE
        // Страховка от оборванного затухания при смене угла: если анимацию
        // прервать между исчезновением и появлением, часы остались бы
        // прозрачными навсегда, и выглядело бы это как «пропали».
        if (show) clockView.alpha = CLOCK_ALPHA
        updateClock()

        handler.removeCallbacks(tick)
        if (show) {
            drift()
            handler.postDelayed(tick, TICK_MILLIS)
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Размеры стали известны — переставить часы, иначе они до первого
        // тика висели бы там, куда их поставил расчёт по нулевой ширине.
        if (clockView.visibility == VISIBLE) drift()
    }

    /**
     * Двигает часы по чуть-чуть.
     *
     * Путь — две синусоиды с несовпадающими периодами: за час набегает пара
     * сантиметров, и в один и тот же пиксель надпись возвращается нескоро.
     * Шаг между обновлениями получается меньше пикселя, поэтому движение не
     * заметно, а выгорания не случается.
     *
     * Раз в полчаса часы переезжают в другой верхний угол — с затуханием, чтобы
     * прыжок не бросался в глаза.
     */
    private fun drift() {
        if (width == 0 || height == 0) return

        val elapsed = (System.currentTimeMillis() - startedAtMillis).toFloat()
        val shouldBeRight = ((elapsed / CORNER_PERIOD_MILLIS).toInt() % 2) == 0
        if (shouldBeRight != atRightCorner) {
            atRightCorner = shouldBeRight
            switchCorner()
            return
        }

        clockView.translationX = positionX(elapsed)
        clockView.translationY = positionY(elapsed)
    }

    private fun positionX(elapsed: Float): Float {
        // Ширину берём измеренную, а не разложенную: до первой раскладки
        // `width` равен нулю, и часы уезжали правым краем за пределы экрана —
        // выглядело так, будто они пропали совсем.
        val clockWidth = maxOf(clockView.width, clockView.measuredWidth)
        // Обе ветки приводим к Float явно: иначе тип получается общим предком
        // Int и Float, и сложение с амплитудой не собирается.
        val base: Float =
            if (atRightCorner) (width - clockWidth - MARGIN).toFloat() else MARGIN.toFloat()
        return base + AMPLITUDE * sin(2 * PI.toFloat() * elapsed / X_PERIOD_MILLIS)
    }

    private fun positionY(elapsed: Float): Float =
        MARGIN.toFloat() + AMPLITUDE * sin(2 * PI.toFloat() * elapsed / Y_PERIOD_MILLIS)

    private fun switchCorner() {
        clockView.animate().cancel()
        clockView.animate()
            .alpha(0f)
            .setDuration(FADE_MILLIS)
            .withEndAction {
                val elapsed = (System.currentTimeMillis() - startedAtMillis).toFloat()
                clockView.translationX = positionX(elapsed)
                clockView.translationY = positionY(elapsed)
                clockView.animate().alpha(CLOCK_ALPHA).setDuration(FADE_MILLIS).start()
            }
            .start()
    }

    private fun updateClock() {
        clockView.text = CLOCK_FORMAT.format(Date())
        // Ширина меняется от «9:05» к «11:48»; без пересчёта надпись у правого
        // края то вылезала бы за экран, то отступала от него.
        clockView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    private companion object {
        const val CLOCK_ALPHA = 0.85f
        const val MARGIN = 56
        const val SOUND_OFFSET = 64
        const val SOUND_FLASH_MILLIS = 3_000L
        const val TICK_MILLIS = 30_000L
        const val FADE_MILLIS = 1_500L

        /** Размах блуждания: пара сантиметров на сорока трёх дюймах. */
        const val AMPLITUDE = 22f

        /** Периоды нарочно разные — тогда путь не повторяется по кругу. */
        const val X_PERIOD_MILLIS = 91f * 60 * 1000
        const val Y_PERIOD_MILLIS = 67f * 60 * 1000

        /** Как часто менять угол. Поровну между двумя — значит вдвое реже выгорание. */
        const val CORNER_PERIOD_MILLIS = 30f * 60 * 1000

        val CLOCK_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
