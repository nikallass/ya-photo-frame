package ru.dvedev.me.yaphotoframe.library

import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.MediaKind
import ru.dvedev.me.yaphotoframe.media.MediaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Библиотека рамки: что лежит в папке и что рамка об этом помнит.
 *
 * Это тот самый единственный шов, через который проверяется движок. Наружу
 * видны только наблюдаемые вещи — что попало в библиотеку, что из этого можно
 * показать, когда был последний обход, — а разбор ответов хранилища, обход
 * дерева и запись на диск наблюдаются через них.
 *
 * @param clock источник времени; в тестах подменяется, чтобы поведение не
 *   зависело от того, когда их запустили.
 */
class MediaLibrary(
    private val source: MediaSource,
    private val store: LibraryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var snapshot: LibrarySnapshot = store.load()

    /**
     * Отложенная запись индекса.
     *
     * Отметка о показе меняет одно число, но на диск ложится весь индекс. На
     * шести тысячах снимков это почти четыре мегабайта JSON, и раньше он
     * сериализовался в главном потоке при каждой смене кадра — то есть ровно в
     * момент перехода, когда экран занят анимацией.
     *
     * Теперь запись уходит в отдельный поток и склеивается: подряд идущие
     * отметки дают одну запись. Потерять при внезапном выключении можно только
     * несколько последних отметок, и это не беда — порядок показа от них почти
     * не зависит.
     */
    private val saver = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "library-saver").apply { isDaemon = true }
    }
    private val savePending = AtomicBoolean(false)

    /** Всё, что известно о папке, включая непоказываемое. */
    val entries: List<LibraryEntry> get() = snapshot.entries

    /** Когда последний раз удалось обойти папку; ноль — ни разу. */
    val syncedAtMillis: Long get() = snapshot.syncedAtMillis

    /** Всё, что есть чем показать, включая видео. */
    fun showable(): List<LibraryEntry> = snapshot.entries.filter { it.item.isShowable }

    /** Фотографии, которые есть чем показать. */
    fun showablePhotos(): List<LibraryEntry> =
        snapshot.entries.filter { it.item.kind == MediaKind.PHOTO && it.item.isShowable }

    /**
     * Обходит папку заново и запоминает результат.
     *
     * Память о показах переносится на совпадающие пути: обход обновляет сведения
     * о файлах, но не должен обнулять порядок показа.
     */
    suspend fun sync(inScope: (String) -> Boolean = { true }): SyncOutcome {
        val fresh = source.list()
        // Обход отменили (сменился отбор) — его результат уже никому не нужен,
        // и подменять им индекс нельзя.
        currentCoroutineContext().ensureActive()
        val remembered = snapshot.entries.associateBy { it.item.path }
        // Первый обход не делает свежим всё подряд: тогда бонусу не на что
        // действовать. А вот появившееся в уже живой библиотеке — из
        // добавленной на Диск или только что отмеченной папки — свежее.
        val firstSync = remembered.isEmpty()
        val now = clock()

        // То, что вне отбора, обход не видел — и трогать его нельзя: владелец
        // снял галочку с папки не для того, чтобы потерять её кэш и память о
        // показах. Вернёт галочку — всё на месте. Показу такие записи не
        // мешают: движок отбирает кандидатов по текущему отбору.
        val kept = snapshot.entries.filter { !inScope(it.item.path) }
        val entries = kept + fresh.map { item ->
            val known = remembered[item.path]
            LibraryEntry(
                item = item,
                lastShownAtMillis = known?.lastShownAtMillis,
                // Известному его отметка остаётся какой была, пусть и пустой:
                // иначе каждый обход «впервые видел» бы всю библиотеку.
                firstSeenAtMillis =
                    if (known != null) known.firstSeenAtMillis else now.takeUnless { firstSync },
                previewLongSidePx = known?.previewLongSidePx,
            )
        }

        val known = fresh.mapTo(mutableSetOf()) { it.path }
        val removed = remembered.keys.count { it !in known && inScope(it) }
        val added = fresh.count { it.path !in remembered }

        synchronized(this) { snapshot = LibrarySnapshot(syncedAtMillis = now, entries = entries) }
        // Обход и так идёт в фоновом потоке, и его результат терять нельзя —
        // пишем сразу.
        store.save(snapshot)

        return SyncOutcome(
            total = entries.size,
            photos = entries.count { it.item.kind == MediaKind.PHOTO },
            videos = entries.count { it.item.kind == MediaKind.VIDEO },
            unshowable = entries.count { !it.item.isShowable },
            added = added,
            removed = removed,
        )
    }

    /** Отмечает элемент показанным — это и есть память, переживающая перезагрузку. */
    @Synchronized
    fun markShown(path: String) {
        val index = snapshot.entries.indexOfFirst { it.item.path == path }
        if (index < 0) return

        val updated = snapshot.entries.toMutableList()
        updated[index] = updated[index].copy(lastShownAtMillis = clock())
        snapshot = snapshot.copy(entries = updated)
        scheduleSave()
    }

    /** Подменяет сведения об элементе, не трогая память о показах. */
    @Synchronized
    fun updateItem(item: MediaItem) {
        val index = snapshot.entries.indexOfFirst { it.item.path == item.path }
        if (index < 0) return

        val updated = snapshot.entries.toMutableList()
        updated[index] = updated[index].copy(item = item)
        snapshot = snapshot.copy(entries = updated)
        scheduleSave()
    }

    /** Запоминает измеренный размер уменьшенной копии. */
    @Synchronized
    fun recordPreviewSize(path: String, longSidePx: Int) {
        val index = snapshot.entries.indexOfFirst { it.item.path == path }
        if (index < 0) return

        val updated = snapshot.entries.toMutableList()
        updated[index] = updated[index].copy(previewLongSidePx = longSidePx)
        snapshot = snapshot.copy(entries = updated)
        scheduleSave()
    }

    private var byPath: Map<String, LibraryEntry> = emptyMap()
    private var byPathOf: LibrarySnapshot? = null

    /**
     * Элемент по пути.
     *
     * Карта строится лениво и живёт, пока не сменился снимок: перебор списка
     * на каждый запрос при тысячах элементов и сотнях запросов на набор
     * очереди — миллионы сравнений, и это на телевизоре.
     */
    @Synchronized
    fun entryOf(path: String): LibraryEntry? {
        val current = snapshot
        if (byPathOf !== current) {
            byPath = current.entries.associateBy { it.item.path }
            byPathOf = current
        }
        return byPath[path]
    }

    /** Запоминает длительность ролика; 0 — заголовок не разобрался. */
    @Synchronized
    fun recordDuration(path: String, millis: Long) {
        val index = snapshot.entries.indexOfFirst { it.item.path == path }
        if (index < 0) return

        val updated = snapshot.entries.toMutableList()
        updated[index] = updated[index].copy(durationMillis = millis)
        snapshot = snapshot.copy(entries = updated)
        scheduleSave()
    }

    /** Записывает накопленное немедленно — на случай остановки показа. */
    fun flush() {
        savePending.set(false)
        runCatching { store.save(snapshot) }
    }

    private fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        saver.schedule(
            {
                savePending.set(false)
                runCatching { store.save(snapshot) }
            },
            SAVE_DELAY_SECONDS,
            TimeUnit.SECONDS,
        )
    }
}

private const val SAVE_DELAY_SECONDS = 20L

/** Что изменилось за обход — то, что показывает диагностика. */
data class SyncOutcome(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val unshowable: Int,
    val added: Int,
    val removed: Int,
)
