package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Динамик — включённый или перечёркнутый.
 *
 * Рисуется сам, а не берётся из шрифта: эмодзи на телевизоре то цветной, то
 * квадратик, а значок, который на одном телевизоре есть, на другом нет,
 * выглядит поломкой.
 */
class SpeakerIcon(context: Context) : View(context) {

    var muted = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }
    private val body = Path()
    private val arc = RectF()

    init {
        // Тень рисуется только программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SIZE, SIZE)
    }

    override fun onDraw(canvas: Canvas) {
        val s = SIZE.toFloat()
        val unit = s / 12
        stroke.strokeWidth = unit * 0.9f

        // Корпус: прямоугольник магнита и раструб.
        body.reset()
        body.moveTo(unit * 1.5f, unit * 4.5f)
        body.lineTo(unit * 4f, unit * 4.5f)
        body.lineTo(unit * 7f, unit * 2f)
        body.lineTo(unit * 7f, unit * 10f)
        body.lineTo(unit * 4f, unit * 7.5f)
        body.lineTo(unit * 1.5f, unit * 7.5f)
        body.close()
        canvas.drawPath(body, fill)

        if (muted) {
            // Косая черта через весь значок: читается с дивана однозначно.
            canvas.drawLine(unit * 2f, unit * 10.5f, unit * 10.5f, unit * 1.5f, stroke)
        } else {
            for (radius in listOf(unit * 2f, unit * 3.6f)) {
                arc.set(unit * 7.5f - radius, s / 2 - radius, unit * 7.5f + radius, s / 2 + radius)
                canvas.drawArc(arc, -45f, 90f, false, stroke)
            }
        }
    }

    private companion object {
        const val SIZE = 60
    }
}
