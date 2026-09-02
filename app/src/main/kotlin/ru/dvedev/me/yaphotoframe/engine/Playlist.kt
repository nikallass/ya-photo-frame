package ru.dvedev.me.yaphotoframe.engine

import ru.dvedev.me.yaphotoframe.library.LibraryEntry
import kotlin.random.Random

/**
 * Кто показывается следующим.
 *
 * Не просто перемешивание: при полусотне снимков чистая случайность выдаёт
 * повторы через раз, и рамка выглядит сломанной. Поэтому недавно показанное
 * временно выбывает совсем, затем возвращается с пониженным весом, а недавно
 * добавленное, наоборот, всплывает — владелец подкидывает фотографии в папку и
 * хочет их увидеть.
 *
 * Свежесть считается по дате появления файла в хранилище, а не по дате съёмки:
 * в папку кладут снимки двадцатилетней давности, и «недавно добавленное» — это
 * про добавление.
 *
 * Выбор случайный, но при заданном зерне и часах — воспроизводимый, иначе
 * порядок показа было бы нечем проверить.
 */
class Playlist(
    private val random: Random,
    /** Читается на каждом выборе: окно свежести настраивается на ходу. */
    private val tuning: () -> PlaylistTuning = { PlaylistTuning() },
) {

    fun pick(
        candidates: List<LibraryEntry>,
        exclude: Set<String>,
        nowMillis: Long,
    ): LibraryEntry? {
        val pool = candidates.filter { it.item.path !in exclude }
        if (pool.isEmpty()) return null
        if (pool.size == 1) return pool.first()

        val eligible = afterHardCooldown(pool)
        // Границы «давности» считаются один раз на выбор, а не на каждого
        // кандидата. Раньше это делалось внутри цикла — квадрат от размера
        // библиотеки: на шести тысячах снимков выбор одного кадра стоил
        // десятков миллионов операций на главном потоке, и кадр замирал на
        // пару секунд ровно перед сменой.
        val shownTimes = eligible.mapNotNull { it.lastShownAtMillis }
        val span = if (shownTimes.size < 2) null else Span(shownTimes.min(), shownTimes.max())
        val weights = eligible.map { weightOf(it, span, nowMillis) }
        return chooseWeighted(eligible, weights)
    }

    /**
     * Убирает из выбора самое недавно показанное.
     *
     * Никогда не показанное считается показанным бесконечно давно и потому
     * выбывает последним.
     */
    private fun afterHardCooldown(pool: List<LibraryEntry>): List<LibraryEntry> {
        val banned = (pool.size * tuning().hardCooldownFraction).toInt()
        if (banned <= 0) return pool

        // Запрещать можно только то, что действительно показывали. Раньше в
        // сортировку попадало и никогда не показанное — с одинаковым временем,
        // в порядке обхода хранилища, — и половина запрета выкашивала первую по
        // порядку папку целиком. На живой библиотеке это дало 76 % показов из
        // папки, где лежит 42 % снимков.
        val recentlyShown = pool
            .filter { it.lastShownAtMillis != null }
            .sortedByDescending { it.lastShownAtMillis }
            .take(banned)
            .mapTo(HashSet()) { it.item.path }
        if (recentlyShown.isEmpty()) return pool

        val allowed = pool.filter { it.item.path !in recentlyShown }
        // Запрет не должен запирать выбор насухо.
        return allowed.ifEmpty { pool }
    }

    /** Самый давний и самый недавний показ среди кандидатов. */
    private class Span(val oldest: Long, val newest: Long)

    private fun weightOf(entry: LibraryEntry, span: Span?, nowMillis: Long): Float =
        recencyWeight(entry, span) * freshnessMultiplier(entry, nowMillis)

    /** Чем дольше не показывали, тем выше вес; никогда не показанное — на самом верху. */
    private fun recencyWeight(entry: LibraryEntry, span: Span?): Float {
        val lastShown = entry.lastShownAtMillis ?: return 1f
        if (span == null || span.newest == span.oldest) return 1f

        val position = (lastShown - span.oldest).toFloat() / (span.newest - span.oldest)
        return 1f - position * (1f - tuning().softCooldownFloor)
    }

    /** Недавно добавленное показывается охотнее, и бонус сходит на нет со временем. */
    private fun freshnessMultiplier(entry: LibraryEntry, nowMillis: Long): Float {
        val addedAt = entry.item.addedAtMillis ?: return 1f
        val age = nowMillis - addedAt
        if (age < 0 || age >= tuning().freshnessWindowMillis) return 1f

        val freshness = 1f - age.toFloat() / tuning().freshnessWindowMillis
        return 1f + tuning().freshnessStrength * freshness
    }

    private fun chooseWeighted(pool: List<LibraryEntry>, weights: List<Float>): LibraryEntry {
        val total = weights.sum()
        if (total <= 0f) return pool[random.nextInt(pool.size)]

        var point = random.nextFloat() * total
        for (index in pool.indices) {
            point -= weights[index]
            if (point <= 0f) return pool[index]
        }
        return pool.last()
    }
}
