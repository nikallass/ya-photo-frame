package ru.dvedev.me.yaphotoframe.video

/**
 * Битый кадр аппаратного декодера узнаётся по цвету.
 *
 * Декодер MStar на профиле, который ему не по зубам, не падает, а выдаёт
 * кадр полосами: часть строк — неинициализированный YUV, на экране это
 * ядовито-зелёный и пурпурный. В живой съёмке таких пикселей почти не
 * бывает, тем более десятой части кадра. Проверка не зависит от контейнера
 * и профиля, поэтому ловит и то, чего не заявляет [android.media.MediaCodecInfo].
 */
object FrameCorruption {

    /** Какая доля кадра должна быть «мусорной», чтобы признать декодер сломанным. */
    const val THRESHOLD = 0.10f

    /** Доля ядовито-зелёных и пурпурных пикселей в массиве ARGB. */
    fun garbageShare(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var bad = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val neonGreen = g > 200 && r < 90 && b < 90
            val magenta = r > 180 && b > 180 && g < 90
            if (neonGreen || magenta) bad++
        }
        return bad.toFloat() / pixels.size
    }

    fun looksCorrupted(pixels: IntArray): Boolean = garbageShare(pixels) >= THRESHOLD
}
