package ru.dvedev.me.yaphotoframe.ui

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Мягкий фон из кадра.
 *
 * Настоящего размытия на целевом устройстве нет: `RenderEffect` требует API 31, а
 * телевизор живёт на API 30. Поэтому фон получается уменьшением до нескольких
 * пикселей и обратным увеличением — на экране остаются только цветовые пятна,
 * объекты неразличимы.
 *
 * Уменьшение идёт **каскадом с половинным шагом**: одно резкое уменьшение в
 * десяток раз билинейная фильтрация делает выборкой, а не усреднением, и в
 * результате сквозь фон проступают отдельные пиксели исходника. Половинные шаги
 * усредняют честно. Увеличение тоже каскадом — иначе один билинейный апскейл с
 * десяти пикселей до размера экрана даёт характерные грани.
 */
object BackgroundBlur {

    /** До скольких пикселей по длинной стороне ужимать. Меньше — размытее. */
    const val DEFAULT_SAMPLE_LONG_SIDE = 32

    /** До какого размера разворачивать обратно перед выводом на экран. */
    private const val INTERMEDIATE_LONG_SIDE = 128

    fun render(source: Bitmap, sampleLongSide: Int = DEFAULT_SAMPLE_LONG_SIDE): Bitmap {
        val target = sampleLongSide.coerceIn(2, 64)
        val tiny = downscaleByHalves(source, target)
        return upscaleByDoubling(tiny, INTERMEDIATE_LONG_SIDE)
    }

    private fun downscaleByHalves(source: Bitmap, targetLongSide: Int): Bitmap {
        var current = source
        var isIntermediate = false

        while (longSide(current) > targetLongSide * 2) {
            val next = scaled(current, max(1, current.width / 2), max(1, current.height / 2))
            if (isIntermediate) current.recycle()
            current = next
            isIntermediate = true
        }

        val ratio = targetLongSide.toFloat() / longSide(current)
        val result = scaled(
            current,
            max(1, (current.width * ratio).roundToInt()),
            max(1, (current.height * ratio).roundToInt()),
        )
        if (isIntermediate) current.recycle()
        return result
    }

    private fun upscaleByDoubling(source: Bitmap, targetLongSide: Int): Bitmap {
        var current = source
        var isIntermediate = false

        while (longSide(current) * 2 <= targetLongSide) {
            val next = scaled(current, current.width * 2, current.height * 2)
            if (isIntermediate) current.recycle()
            current = next
            isIntermediate = true
        }

        if (current === source) {
            // Ни одного удвоения не понадобилось — вернуть исходник нельзя,
            // им владеет вызывающий код.
            return scaled(current, current.width, current.height)
        }
        return current
    }

    private fun scaled(source: Bitmap, width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(source, width, height, /* filter = */ true)

    private fun longSide(bitmap: Bitmap): Int = max(bitmap.width, bitmap.height)
}
