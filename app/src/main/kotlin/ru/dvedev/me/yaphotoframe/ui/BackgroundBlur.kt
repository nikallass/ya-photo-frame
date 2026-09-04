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

    /**
     * До какого размера разворачивать обратно перед выводом на экран.
     *
     * Было 128: остаток до 1920 добирал один билинейный апскейл в пятнадцать
     * раз, и при слабом размытии на фоне проступала сетка мягких квадратов —
     * каждый пиксель выборки виден как пятно с прямыми гранями. 512 оставляет
     * экрану увеличение меньше чем вчетверо, и грани не читаются.
     */
    private const val INTERMEDIATE_LONG_SIDE = 512

    fun render(source: Bitmap, sampleLongSide: Int = DEFAULT_SAMPLE_LONG_SIDE): Bitmap {
        val target = sampleLongSide.coerceIn(2, 64)
        val tiny = downscaleByHalves(source, target)
        // Сгладить на малом размере: соседние выборки перестают быть ступенькой
        // ещё до разворота, и удвоения дальше только растягивают плавный переход.
        val soft = softened(tiny)
        tiny.recycle()
        return upscaleByDoubling(soft, INTERMEDIATE_LONG_SIDE)
    }

    /** Усреднение с соседями 3×3, два прохода — почти гауссово, на десятках пикселей это даром. */
    private fun softened(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        var pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(2) { pixels = boxPass(pixels, w, h) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val p = src[yy * w + xx]
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            out[y * w + x] = (0xff shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
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
