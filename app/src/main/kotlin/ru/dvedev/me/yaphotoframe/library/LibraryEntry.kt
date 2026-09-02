package ru.dvedev.me.yaphotoframe.library

import ru.dvedev.me.yaphotoframe.media.MediaItem

/**
 * Элемент библиотеки вместе с тем, что рамка о нём помнит.
 *
 * Сам [item] приходит из хранилища и при каждом обходе перезаписывается, а
 * [lastShownAtMillis] принадлежит рамке и обход переживает: без него после
 * перезагрузки телевизора порядок показа начинался бы с чистого листа.
 */
data class LibraryEntry(
    val item: MediaItem,
    val lastShownAtMillis: Long? = null,
    /**
     * Когда снимок впервые попал в библиотеку, если она к тому моменту уже
     * была. Пусто у всего, что пришло первым обходом. Нужно для свежести:
     * снимок из только что отмеченной подпапки залит, может, год назад, но на
     * рамке не бывал — и для владельца он новый.
     */
    val firstSeenAtMillis: Long? = null,
    /**
     * Длинная сторона уменьшенной копии в пикселях, когда её удалось измерить.
     * Хранилище размеров не отдаёт, поэтому известно только после скачивания.
     */
    val previewLongSidePx: Int? = null,
) {
    /** Мельче ли снимок порога; неизмеренное мелким не считается. */
    fun isSmallerThan(minLongSidePx: Int): Boolean =
        minLongSidePx > 0 &&
            item.kind == ru.dvedev.me.yaphotoframe.media.MediaKind.PHOTO &&
            previewLongSidePx?.let { it < minLongSidePx } == true
}
