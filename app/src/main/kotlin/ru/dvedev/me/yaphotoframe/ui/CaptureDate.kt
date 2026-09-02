package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Подпись с датой съёмки — в углу самого снимка, а не экрана.
 *
 * Рядом с часами она читалась как сегодняшнее число: две строки цифр подряд
 * воспринимаются как одно целое. У снимка в углу — уже свойство снимка, и
 * никакой путаницы. У пары снимков подпись своя у каждого, иначе непонятно, к
 * которому она относится.
 */
object CaptureDate {

    /** Без числа: «апрель 2016» теплее и не спорит с часами. */
    private val FORMAT = SimpleDateFormat("LLLL yyyy", Locale("ru"))

    fun format(millis: Long?): String? = millis?.let { FORMAT.format(Date(it)) }

    fun label(context: Context) = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        alpha = 0.55f
        setShadowLayer(8f, 0f, 1f, Color.BLACK)
        gravity = Gravity.END
        setPadding(PADDING, PADDING / 2, PADDING, PADDING / 2)
    }

    const val PADDING = 18
}
