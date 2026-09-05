package ru.dvedev.me.yaphotoframe.ui

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * План показа кадра: где он встаёт, куда и на сколько едет, на сколько
 * вырастает. Чистая арифметика без View, чтобы проверяться на JVM: слой с
 * картинками только применяет готовые числа.
 *
 * Правила (спецификация 1.3, промоделированы на 4 704 конфигурациях):
 * - снимок никогда не увеличивается; размер задаётся отдельно для
 *   горизонтальных и для вертикальных (и пар);
 * - смещение к золотому сечению — доля *свободного места* до отступа, а не
 *   доля экрана: большой кадр смещается немного, маленький — заметно, и никто
 *   не прижимается к отступу; величина доли варьируется от кадра к кадру;
 * - ход привязан к золотой точке: наружу — кадр приезжает в неё к концу
 *   показа, внутрь — уезжает из неё; путь режется свободным местом, так что
 *   отступ не пересекается никогда;
 * - путь и рост заданы за 20 секунд и пересчитываются по времени показа:
 *   скорость не зависит от того, сколько висит кадр;
 * - рост ограничен свободным местом по тесной оси: кадр у отступа не растёт.
 */
object FramePlan {

    /** Путь и рост в настройках заданы за столько показа. */
    const val REFERENCE_MILLIS = 20_000L

    /** Потолок роста при долгом показе: дальше кадр уже не «дышит», а наезжает. */
    const val MAX_GROWTH = 0.20f

    /** Короче двух десятых секунды ход не бывает: иначе это рывок, а не движение. */
    const val MIN_DURATION_MILLIS = 200L

    /** Ближе пикселя к отступу не подходим: округление иначе ставило край ровно на линию. */
    private const val SLACK_PX = 1f

    private const val NEAR = 0.382f
    private const val FAR = 0.618f

    data class Input(
        val width: Int,
        val height: Int,
        /** Размер ряда до масштабирования: снимок или два снимка с просветом. */
        val naturalWidth: Int,
        val naturalHeight: Int,
        /** Вертикальный снимок или пара — свой размер кадра. */
        val portrait: Boolean,
        /** Ключ разнообразия: путь файла плюс время прошлого показа. */
        val key: String,
        val settings: FrameSettings,
        /** Сколько кадр будет на экране, вместе с растворением. */
        val durationMillis: Long,
    )

    data class Plan(
        val scale: Float,
        val drawnWidth: Int,
        val drawnHeight: Int,
        /** Левый верхний угол ряда в начале показа. */
        val left: Int,
        val top: Int,
        /** Сдвиг за показ, со знаком. */
        val travelX: Float,
        val travelY: Float,
        /** На сколько ряд вырастет за показ, долей от себя. */
        val growth: Float,
        val durationMillis: Long,
    ) {
        /** Во сколько раз ряд больше в конце показа. */
        val toScale: Float get() = 1f + growth
    }

    /** Во сколько раз ужать ряд, чтобы он влез в свою долю экрана; больше единицы не бывает. */
    fun scaleFor(input: Input): Float {
        val inset = if (input.portrait) input.settings.frameInsetPortrait else input.settings.frameInsetLandscape
        return min(
            1f,
            min(
                input.width * inset / input.naturalWidth,
                input.height * inset / input.naturalHeight,
            ),
        )
    }

    fun compute(input: Input): Plan {
        val s = input.settings
        val scale = scaleFor(input)
        val drawnW = (input.naturalWidth * scale).roundToInt()
        val drawnH = (input.naturalHeight * scale).roundToInt()
        val duration = input.durationMillis.coerceAtLeast(MIN_DURATION_MILLIS)
        val factor = duration.toFloat() / REFERENCE_MILLIS

        // Рост: за 20 секунд — по настройке, дальше пропорционально, но не
        // больше потолка и не больше, чем позволяет место до отступа.
        var growth = min(MAX_GROWTH, s.zoomAmount.coerceAtLeast(0f) * factor)
        for ((avail, drawn) in listOf(input.width to drawnW, input.height to drawnH)) {
            val free = (avail - drawn) / 2f - avail * s.edgeMargin.coerceAtLeast(0f) - SLACK_PX
            growth = min(growth, (2f * free / drawn).coerceAtLeast(0f))
        }

        val wanted = input.width * s.driftAmplitude.coerceAtLeast(0f) * factor
        val hash = input.key.hashCode()
        val signX = if (hash and 1 == 0) -1f else 1f   // NEAR — левее, FAR — правее
        val signY = if (hash and 2 == 0) -1f else 1f
        val dirX = if (hash and 4 == 0) 1f else -1f
        val dirY = if (hash and 8 == 0) 1f else -1f
        val kX = 0.6f + 0.4f * ((hash ushr 8) and 0xFF) / 256f
        val kY = 0.6f + 0.4f * ((hash ushr 16) and 0xFF) / 256f

        val (centerX, travelX) = axis(input.width, drawnW, growth, s, wanted, signX, dirX, kX)
        val (centerY, travelY) = axis(input.height, drawnH, growth, s, wanted, signY, dirY, kY)

        return Plan(
            scale = scale,
            drawnWidth = drawnW,
            drawnHeight = drawnH,
            left = (centerX - drawnW / 2f).roundToInt(),
            top = (centerY - drawnH / 2f).roundToInt(),
            travelX = travelX,
            travelY = travelY,
            growth = growth,
            durationMillis = duration,
        )
    }

    /**
     * Одна ось: где середина ряда в начале показа и на сколько она сдвинется.
     *
     * [sign] — в какую сторону от центра золотая точка, [dir] — куда ехать,
     * [k] — какую долю свободного места занимает смещение при «Смещении 1».
     */
    private fun axis(
        avail: Int,
        drawn: Int,
        growth: Float,
        s: FrameSettings,
        wanted: Float,
        sign: Float,
        dir: Float,
        k: Float,
    ): Pair<Float, Float> {
        val middle = avail / 2f
        val room = (avail - drawn) / 2f - avail * s.edgeMargin.coerceAtLeast(0f) -
            drawn * growth / 2f - SLACK_PX
        if (room <= 0f) return middle to 0f
        val offset = room * s.placementStrength.coerceIn(0f, 1f) * k
        val path = min(wanted, offset + room)
        val (start, end) = if (dir == sign) {
            sign * (offset - path) to sign * offset      // наружу: приезжает в золотую точку
        } else {
            sign * offset to sign * (offset - path)      // внутрь: уезжает из неё
        }
        return (middle + start) to (end - start)
    }

    /** Четверть золотого сечения по ключу — для диагностики и тестов. */
    fun goldenPoint(key: String): Pair<Float, Float> {
        val hash = key.hashCode()
        return (if (hash and 1 == 0) NEAR else FAR) to (if (hash and 2 == 0) NEAR else FAR)
    }

    /** Ключ показа: тот же снимок в другой раз встаёт иначе, а во время показа не прыгает. */
    fun showKey(path: String, lastShownAtMillis: Long?): String =
        if (lastShownAtMillis == null) path else "$path#$lastShownAtMillis"
}
