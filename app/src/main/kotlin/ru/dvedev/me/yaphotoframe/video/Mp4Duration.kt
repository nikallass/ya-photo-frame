package ru.dvedev.me.yaphotoframe.video

/**
 * Длительность ролика по заголовку контейнера MP4/MOV.
 *
 * Диск не отдаёт длительность, а без неё не посчитать битрейт — единственное,
 * по чему видно, пройдёт ролик по каналу или нет. Качать ролик ради этого нельзя:
 * он на то и тяжёлый. Зато у контейнера есть атом `moov` с `mvhd`, где лежат
 * шкала времени и длительность; у камер и iPhone он в начале файла, у
 * Android-телефонов — в конце, после `mdat`, но и тогда его смещение известно
 * из размера `mdat`. Двух кусков по 64 КБ хватает почти всегда.
 *
 * Разбор чистый: ни сети, ни Android — проверяется обычным тестом.
 */
object Mp4Duration {

    sealed class Outcome {
        /** Длительность найдена. */
        data class Found(val millis: Long) : Outcome()

        /** В этом куске атома `moov` нет, он начинается со смещения [offset]. */
        data class MoovAt(val offset: Long) : Outcome()

        /** Это не MP4 или заголовок разобрать не удалось. */
        object Unknown : Outcome()
    }

    /** Сколько байт просить за один заход. */
    const val CHUNK_BYTES = 64 * 1024

    /**
     * Разбирает кусок файла, начинающийся со смещения [baseOffset].
     *
     * Кусок должен начинаться на границе атома: первый — с нуля, следующий — с
     * того смещения, которое вернул предыдущий разбор.
     */
    fun scan(bytes: ByteArray, baseOffset: Long, fileSize: Long): Outcome {
        var position = 0
        var sawBox = false
        while (position + HEADER_BYTES <= bytes.size) {
            var size = u32(bytes, position)
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            var header = HEADER_BYTES
            when (size) {
                1L -> {
                    if (position + 16 > bytes.size) return Outcome.Unknown
                    size = u64(bytes, position + 8)
                    header = 16
                }
                0L -> size = fileSize - (baseOffset + position)
            }
            if (size < header) return Outcome.Unknown
            if (!sawBox) {
                // Первый атом должен быть знакомым, иначе это не MP4 вовсе.
                if (type !in KNOWN_TYPES) return Outcome.Unknown
                sawBox = true
            }
            if (type == "moov") return mvhd(bytes, position + header, minOf(bytes.size.toLong(), position + size).toInt())
            val next = position + size
            if (next > bytes.size) {
                val absolute = baseOffset + next
                return if (absolute < fileSize) Outcome.MoovAt(absolute) else Outcome.Unknown
            }
            position = next.toInt()
        }
        return Outcome.Unknown
    }

    private fun mvhd(bytes: ByteArray, from: Int, to: Int): Outcome {
        var position = from
        while (position + HEADER_BYTES <= to) {
            var size = u32(bytes, position)
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            var header = HEADER_BYTES
            if (size == 1L) {
                if (position + 16 > to) return Outcome.Unknown
                size = u64(bytes, position + 8)
                header = 16
            }
            if (size < header) return Outcome.Unknown
            if (type == "mvhd") {
                val payload = position + header
                if (payload + 4 > to) return Outcome.Unknown
                val version = bytes[payload].toInt() and 0xFF
                val timescaleAt = if (version == 1) payload + 20 else payload + 12
                val durationAt = if (version == 1) payload + 24 else payload + 16
                val durationEnd = durationAt + if (version == 1) 8 else 4
                if (durationEnd > to) return Outcome.Unknown
                val timescale = u32(bytes, timescaleAt)
                val duration = if (version == 1) u64(bytes, durationAt) else u32(bytes, durationAt)
                if (timescale <= 0 || duration <= 0) return Outcome.Unknown
                return Outcome.Found(duration * 1000 / timescale)
            }
            position += size.toInt()
        }
        return Outcome.Unknown
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)

    private const val HEADER_BYTES = 8

    private val KNOWN_TYPES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "moof", "styp", "sidx", "pdin")
}
