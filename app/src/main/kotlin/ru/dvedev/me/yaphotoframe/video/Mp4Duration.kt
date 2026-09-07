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
        data class Found(val millis: Long, val codec: String? = null) : Outcome()

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
            if (type == "moov") {
                val from = position + header
                val to = minOf(bytes.size.toLong(), position + size).toInt()
                val found = mvhd(bytes, from, to)
                return if (found is Outcome.Found) found.copy(codec = codecIn(bytes, from, to)) else found
            }
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

    /**
     * Кодек первой видеодорожки в виде строки `codecs`, как в HTML5/ExoPlayer:
     * `hvc1.2.4.L153.B0` для HEVC Main 10, `avc1.640028` для H.264 High 4.0.
     * Ищется в `moov/trak/mdia/minf/stbl/stsd`; null — не нашли или не знаем.
     */
    fun codecIn(bytes: ByteArray, from: Int, to: Int): String? {
        var position = from
        while (position + HEADER_BYTES <= to) {
            var size = u32(bytes, position)
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            var header = HEADER_BYTES
            if (size == 1L) {
                if (position + 16 > to) return null
                size = u64(bytes, position + 8)
                header = 16
            }
            if (size < header) return null
            val end = minOf(to.toLong(), position + size).toInt()
            when (type) {
                "trak", "mdia", "minf", "stbl" -> codecIn(bytes, position + header, end)?.let { return it }
                "stsd" -> return sampleEntryCodec(bytes, position + header, end)
            }
            position = end
        }
        return null
    }

    private fun sampleEntryCodec(bytes: ByteArray, from: Int, to: Int): String? {
        // version(1) flags(3) entry_count(4), затем первая запись.
        val entry = from + 8
        if (entry + HEADER_BYTES > to) return null
        val entrySize = u32(bytes, entry)
        val entryType = String(bytes, entry + 4, 4, Charsets.ISO_8859_1)
        val entryEnd = minOf(to.toLong(), entry + entrySize).toInt()
        // VisualSampleEntry: 8 заголовка + 78 полей, дальше дочерние атомы.
        var position = entry + 86
        while (position + HEADER_BYTES <= entryEnd) {
            val size = u32(bytes, position)
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            if (size < HEADER_BYTES) return null
            val payload = position + HEADER_BYTES
            val end = minOf(entryEnd.toLong(), position + size).toInt()
            when (type) {
                "hvcC" -> return hevcCodec(entryType, bytes, payload, end)
                "avcC" -> return avcCodec(entryType, bytes, payload, end)
            }
            position = end
        }
        return when (entryType) {
            "hvc1", "hev1", "avc1", "avc3", "mp4v" -> entryType
            else -> null
        }
    }

    private fun hevcCodec(entryType: String, bytes: ByteArray, at: Int, end: Int): String? {
        if (at + 18 > end) return entryType
        val profileByte = bytes[at + 1].toInt() and 0xFF
        val profileSpace = profileByte ushr 6
        val tier = (profileByte ushr 5) and 1
        val profileIdc = profileByte and 0x1F
        // Флаги совместимости хранятся старшим битом вперёд, в строке — задом наперёд.
        var compat = 0L
        for (bit in 0 until 32) {
            val byte = bytes[at + 2 + bit / 8].toInt() and 0xFF
            if ((byte ushr (7 - bit % 8)) and 1 == 1) compat = compat or (1L shl bit)
        }
        val level = bytes[at + 12].toInt() and 0xFF
        val chroma = bytes[at + 16].toInt() and 3
        val bitDepth = (bytes[at + 17].toInt() and 7) + 8
        val space = when (profileSpace) { 1 -> "A"; 2 -> "B"; 3 -> "C"; else -> "" }
        val extra = if (bitDepth != 8 || chroma != 1) ".${bitDepth}bit${if (chroma == 2) "422" else if (chroma == 3) "444" else ""}" else ""
        return "$entryType.$space$profileIdc.${java.lang.Long.toHexString(compat).uppercase()}.${if (tier == 1) "H" else "L"}$level.B0$extra"
    }

    private fun avcCodec(entryType: String, bytes: ByteArray, at: Int, end: Int): String? {
        if (at + 4 > end) return entryType
        val profile = bytes[at + 1].toInt() and 0xFF
        val constraints = bytes[at + 2].toInt() and 0xFF
        val level = bytes[at + 3].toInt() and 0xFF
        return "%s.%02X%02X%02X".format(entryType, profile, constraints, level)
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
