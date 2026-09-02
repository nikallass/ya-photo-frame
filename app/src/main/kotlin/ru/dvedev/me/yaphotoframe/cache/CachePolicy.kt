package ru.dvedev.me.yaphotoframe.cache

/**
 * Правила кэша — одни на фотографии и на видео.
 *
 * Живут отдельно от настроек внешнего вида, чтобы движок ничего не знал про
 * экран: он проверяется обычным юнит-тестом и не должен тянуть за собой Android.
 */
data class CachePolicy(
    /**
     * Сколько всего места отдано под кэш.
     *
     * Гигабайта хватает на несколько тысяч уменьшенных копий: при двухстах
     * килобайтах на снимок библиотека помещается целиком, и полное локальное
     * зеркало получается само собой, а не отдельным режимом.
     */
    val budgetBytes: Long = DEFAULT_BUDGET_BYTES,

    /**
     * Выше какого размера файл не кладут в кэш вовсе.
     *
     * Ролик с телефона осядет на устройстве и будет играть с диска;
     * многогигабайтная съёмка с фотоаппарата пойдёт потоком и места не займёт.
     */
    val itemThresholdBytes: Long = DEFAULT_ITEM_THRESHOLD_BYTES,

    /** На сколько элементов вперёд смотреть и, значит, что подгружать заранее. */
    val prefetchCount: Int = DEFAULT_PREFETCH_COUNT,
) {
    companion object {
        const val DEFAULT_BUDGET_BYTES = 1024L * 1024 * 1024
        const val DEFAULT_ITEM_THRESHOLD_BYTES = 150L * 1024 * 1024
        const val DEFAULT_PREFETCH_COUNT = 10

        const val MIN_BUDGET_BYTES = 64L * 1024 * 1024
        const val MAX_BUDGET_BYTES = 8L * 1024 * 1024 * 1024
    }
}
