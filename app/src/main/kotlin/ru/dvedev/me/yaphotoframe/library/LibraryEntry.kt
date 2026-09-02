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
)
