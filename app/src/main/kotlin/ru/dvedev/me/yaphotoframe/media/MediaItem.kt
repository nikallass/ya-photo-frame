package ru.dvedev.me.yaphotoframe.media

/** Что за файл: рамка показывает фотографии, видео пока только индексируются. */
enum class MediaKind { PHOTO, VIDEO }

/**
 * Размер уменьшенной копии, которую отдаёт хранилище.
 *
 * Значения совпадают с теми, что понимает Яндекс.Диск. Замерено на живом API:
 * [MICRO] — 150 пикселей по длинной стороне, [FULL] — 1280, и это потолок:
 * запросы большего размера возвращают тот же файл, что и XXXL.
 */
enum class PreviewSize(val apiValue: String) {
    /** Микро-копия под размытый фон: около 6–9 КБ. */
    MICRO("S"),

    /** Максимум, который отдаёт хранилище: 1280 по длинной стороне, около 200 КБ. */
    FULL("XXXL"),
}

/**
 * Ссылка на превью, из которой можно получить любой размер.
 *
 * Подписанный URL превью несёт размер обычным параметром запроса, и подмена этого
 * параметра работает без нового обращения к API (проверено на живом API). Поэтому
 * одного листинга достаточно, чтобы получить и кадр, и фон под него.
 */
@JvmInline
value class PreviewUrl(val template: String) {

    fun at(size: PreviewSize): String = template.replace(SIZE_PLACEHOLDER, size.apiValue)

    companion object {
        private const val SIZE_PLACEHOLDER = "{size}"
        private val SIZE_PARAM = Regex("""([?&]size=)[^&]*""")

        /**
         * Превращает выданный API URL в шаблон. Возвращает null, если размер в ссылке
         * не найден — такую ссылку нельзя пересобрать под другой размер, а значит
         * элемент непригоден для показа.
         */
        fun fromApiUrl(url: String): PreviewUrl? {
            if (!SIZE_PARAM.containsMatchIn(url)) return null
            return PreviewUrl(SIZE_PARAM.replace(url) { "${it.groupValues[1]}$SIZE_PLACEHOLDER" })
        }
    }
}

/**
 * Элемент библиотеки.
 *
 * @param path путь внутри расшаренной папки — он же устойчивый идентификатор.
 * @param takenAtMillis дата съёмки из EXIF. У снимков, прошедших через
 *   мессенджеры и соцсети, её обычно нет вовсе.
 * @param addedAtMillis когда файл появился в хранилище. Именно это, а не дата
 *   съёмки, означает свежесть: владелец подкидывает в папку старые фотографии,
 *   и «недавно добавленное» — про добавление, а не про год съёмки.
 */
data class MediaItem(
    val path: String,
    val name: String,
    val kind: MediaKind,
    val mimeType: String?,
    val sizeBytes: Long,
    val takenAtMillis: Long?,
    val addedAtMillis: Long?,
    /**
     * Ссылка на превью, или null, если хранилище его не отдало.
     *
     * Такой элемент остаётся в индексе — по нему видно, что файл найден, но
     * показать его нечем. Молча выбрасывать было бы хуже: владелец не понял бы,
     * почему снимок не появляется.
     */
    val preview: PreviewUrl?,
) {
    /** Есть ли чем показать этот элемент. */
    val isShowable: Boolean get() = preview != null
}
