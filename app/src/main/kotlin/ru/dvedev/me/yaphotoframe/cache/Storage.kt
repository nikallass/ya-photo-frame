package ru.dvedev.me.yaphotoframe.cache

import ru.dvedev.me.yaphotoframe.media.MediaItem
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import java.io.File

/**
 * Хранилище — одна папка, где рамка держит всё скачанное: копии снимков и
 * видео целиком. Место у хранилища одно: память телевизора или флешка.
 *
 * Внутри две ветки: `previews/` с копиями снимков под хеш-именами и
 * `videos/` с деревом путей как на Диске — флешку можно унести и показать
 * где угодно. Объём задаётся либо явно, либо «свободное место минус запас».
 * Когда места не хватает, вытесняется самое старое по показу, причём видео
 * раньше снимков: снимки маленькие, а без них экран пуст, когда нет сети.
 *
 * Список файлов держится в памяти. Движок спрашивает «лежит ли» и
 * «поместится ли» на каждое видео при каждом наборе очереди, а на флешке с
 * NTFS через FUSE каждый такой вопрос к диску — миллисекунда; на тысячах
 * видео это секунды, и показ вставал. Список ведётся по своим записям, а
 * перечитывается с диска только по просьбе движка между подготовками
 * ([refresh]): обход папки на флешке во время закачки на неё же длится
 * секунды, и на горячем пути ему не место.
 *
 * Размер и давность каждого файла тоже в памяти. Когда флешка заполнилась,
 * вытеснение идёт перед каждой закачкой, и сортировка десяти тысяч файлов,
 * спрашивавшая у флешки время файла прямо в сравнении, занимала минуты —
 * а шла десятками разом: показ вставал на одном кадре.
 */
class Storage(
    val root: File,
    val place: Place,
    private val capacity: () -> Capacity,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Свободное и полное место на диске хранилища — подменяются в тестах. */
    private val usableSpace: () -> Long = { root.usableSpace },
    private val totalSpace: () -> Long = { root.totalSpace },
    /** Ключ файла, вытесненного ради места, — движок помнит, что и когда ушло. */
    private val onEvicted: (String) -> Unit = {},
) {

    /** Где хранилище: память телевизора или флешка с меткой. */
    sealed class Place {
        object TvMemory : Place()
        data class Flash(val uuid: String, val label: String) : Place()
    }

    /** Как считать объём. */
    sealed class Capacity {
        /** Столько байт под хранилище, но не больше, чем есть на диске. */
        data class Fixed(val bytes: Long) : Capacity()

        /** Всё свободное место минус запас. */
        data class ByFree(val reserveBytes: Long) : Capacity()
    }

    private val cache = MediaCache(root, ::budgetBytes, clock)

    /** Файл хранилища, как его помнит список: размер и когда к нему обращались. */
    private class Entry(val file: File, val bytes: Long, @Volatile var usedAtMillis: Long)

    /** Что лежит: ключ → файл, суммарный размер, свободное место на момент чтения. */
    private class Snapshot(
        val files: MutableMap<String, Entry>,
        var bytes: Long,
        var usable: Long,
        val atMillis: Long,
    )

    private var snapshot: Snapshot? = null

    /**
     * Список читается при первом обращении, вне замка: на флешке с тысячами
     * файлов это десятки секунд, и держать всё это время «лежит ли файл»
     * нельзя — первый кадр ждал бы обхода.
     */
    private fun current(): Snapshot {
        peek()?.let { return it }
        // Первый обход — один на всех: кто пришёл вторым, ждёт первого, а не
        // запускает свой рядом с ним на той же флешке.
        synchronized(readLock) {
            peek()?.let { return it }
            synchronized(this) { journal = linkedMapOf() }
            val fresh = read()
            synchronized(this) {
                // Что записали и удалили, пока шёл обход, обход мог не увидеть:
                // скачанное в эти секунды видео иначе считалось бы нескачанным.
                journal?.forEach { (key, entry) ->
                    fresh.files.remove(key)?.let { fresh.bytes -= it.bytes }
                    if (entry != null) {
                        fresh.files[key] = entry
                        fresh.bytes += entry.bytes
                    }
                }
                journal = null
                snapshot = fresh
            }
            return fresh
        }
    }

    /** Записи и удаления за время первого обхода: ключ → файл, null — удалён. */
    private var journal: MutableMap<String, Entry?>? = null

    private val readLock = Any()

    /**
     * Читает список файлов заранее, чтобы первые же вопросы движка не ждали
     * обхода. Зовётся с фонового потока до того, как хранилище отдано движку.
     */
    fun warmUp() {
        current()
    }

    private fun peek(): Snapshot? = synchronized(this) { snapshot }

    /** Обход папки — вне замка, чтобы не держать движок, пока диск занят. */
    private fun read(): Snapshot {
        val scanned = cache.scan()
        val files = scanned.associateByTo(linkedMapOf(), { keyOf(it.file) }, { Entry(it.file, it.bytes, it.usedAtMillis) })
        return Snapshot(files, scanned.sumOf { it.bytes }, usableSpace(), clock())
    }

    /**
     * Обновляет свободное место: его могли съесть снаружи. Сам список файлов
     * с диска больше не перечитывается — он ведётся по своим записям, а обход
     * флешки с тысячами файлов через FUSE длится минуты и тормозит показ.
     * Файл, удалённый руками, обнаружится при обращении и выпадет из списка.
     */
    fun refresh() {
        val snap = peek() ?: run { current(); return }
        val usable = usableSpace()
        synchronized(this) { snap.usable = usable }
    }

    @Synchronized
    private fun noteAdded(key: String, file: File) {
        val snap = snapshot ?: run {
            journal?.put(key, Entry(file, file.length(), clock()))
            return
        }
        val size = file.length()
        snap.files.put(key, Entry(file, size, clock()))?.let { snap.bytes -= it.bytes }
        snap.bytes += size
        snap.usable = (snap.usable - size).coerceAtLeast(0L)
    }

    @Synchronized
    private fun noteRemoved(key: String) {
        val snap = snapshot ?: run {
            journal?.put(key, null)
            return
        }
        val gone = snap.files.remove(key) ?: return
        snap.bytes -= gone.bytes
        snap.usable += gone.bytes
    }

    @Synchronized
    private fun forgetSnapshot() {
        snapshot = null
    }

    /** Копия списка под сортировку — сам список правится под замком. */
    @Synchronized
    private fun listed(snap: Snapshot): List<Pair<String, Entry>> = snap.files.entries.map { it.key to it.value }

    // ── ключи ──

    fun previewKey(item: MediaItem, size: PreviewSize): String = previewKey(item.path, size)

    fun previewKey(path: String, size: PreviewSize): String = PREVIEWS + "/" + CacheKey.forPreview(path, size)

    fun videoKey(path: String): String = VIDEOS + "/" + path.trimStart('/')

    /** Путь на Диске по ключу видео; null — это не видео. */
    fun videoPathOf(key: String): String? =
        if (key.startsWith("$VIDEOS/")) "/" + key.removePrefix("$VIDEOS/") else null

    // ── файлы ──

    /** Пока список ещё не прочитан, спрашиваем у диска напрямую — это один stat, а не обход. */
    fun has(key: String): Boolean {
        synchronized(this) {
            snapshot?.let { return it.files.containsKey(key) }
            journal?.let { if (it.containsKey(key)) return it[key] != null }
        }
        return cache.has(key)
    }

    fun file(key: String): File {
        val file = cache.file(key)
        if (!file.isFile) {
            // Удалили руками или флешку почистили: список об этом не знал.
            noteRemoved(key)
            return file
        }
        val now = clock()
        val entry = synchronized(this) { snapshot?.files?.get(key) }
        // Отметка на диске нужна, чтобы давность пережила перезапуск, но
        // запись на флешку стоит дорого: раз в сутки на файл достаточно.
        if (entry == null || now - entry.usedAtMillis >= TOUCH_EVERY_MILLIS) cache.touch(key)
        entry?.usedAtMillis = now
        return file
    }

    fun put(key: String, write: (File) -> Unit): File {
        val file = cache.put(key, write)
        noteAdded(key, file)
        return file
    }

    fun remove(key: String): Boolean {
        val removed = cache.remove(key)
        if (removed) noteRemoved(key)
        return removed
    }

    /** Убрать всё, что относится к файлу на Диске: копии и само видео. */
    fun forget(path: String) {
        PreviewSize.entries.forEach { remove(previewKey(path, it)) }
        remove(videoKey(path))
    }

    /** Пути видео, которые лежат в хранилище, как на Диске. */
    fun videoPaths(): List<String> =
        listed(current()).map { it.first }.filter { it.startsWith("$VIDEOS/") }.map { "/" + it.removePrefix("$VIDEOS/") }

    fun clear() {
        cache.clear()
        forgetSnapshot()
    }

    // ── объём ──

    fun usedBytes(): Long = current().bytes

    fun usedBytes(kind: Kind): Long =
        listed(current()).filter { kindOf(it.first) == kind }.sumOf { it.second.bytes }

    fun count(kind: Kind): Int = listed(current()).count { kindOf(it.first) == kind }

    fun freeBytes(): Long = current().usable

    fun totalBytes(): Long = totalSpace()

    /** Сколько байт хранилищу можно занимать сейчас. */
    fun budgetBytes(): Long = current().let { budgetFor(it.bytes, it.usable) }

    private fun budgetFor(used: Long, usable: Long): Long = when (val c = capacity()) {
        is Capacity.Fixed -> minOf(c.bytes, used + usable).coerceAtLeast(0L)
        is Capacity.ByFree -> (used + usable - c.reserveBytes).coerceAtLeast(0L)
    }

    /** Поместится ли файл такого размера вообще — хоть после вытеснения всего. */
    fun fits(bytes: Long): Boolean {
        // Список ещё не прочитан — судим по свободному месту, без обхода:
        // первому кадру обход ждать незачем.
        val snap = peek() ?: return bytes <= budgetFor(0L, usableSpace())
        return bytes <= budgetFor(snap.bytes, snap.usable)
    }

    /** Есть ли место под файл прямо сейчас, без вытеснения. */
    private fun roomFor(bytes: Long, used: Long, usable: Long): Boolean =
        used + bytes <= budgetFor(used, usable) && usable >= bytes + reserve()

    /**
     * Освобождает место под файл: вытесняет самое старое по показу, сначала
     * видео, потом снимки, пока файл не поместится. Возвращает, поместился ли.
     *
     * Вытеснение одно на всех и с запасом: когда хранилище заполнено, место
     * нужно перед каждой закачкой, и без запаса каждая копия снимка в двести
     * килобайт запускала бы своё вытеснение.
     */
    fun makeRoom(bytes: Long): Boolean {
        if (!fits(bytes)) return false
        // До первого чтения списка вытеснять нечего и незачем: место есть.
        val first = peek() ?: return usableSpace() >= bytes + reserve()
        if (roomFor(bytes, first.bytes, first.usable)) return true
        synchronized(evictLock) {
            val snap = peek() ?: return true
            var used = snap.bytes
            var usable = snap.usable
            if (roomFor(bytes, used, usable)) return true
            val wanted = bytes + minOf(HEADROOM_MAX_BYTES, budgetFor(used, usable) / HEADROOM_SHARE)
            for ((key, entry) in victims(snap)) {
                if (roomFor(wanted, used, usable)) break
                if (cache.delete(entry.file) || !entry.file.exists()) {
                    noteRemoved(key)
                    used -= entry.bytes
                    usable += entry.bytes
                    onEvicted(key)
                }
            }
            return roomFor(bytes, used, usable)
        }
    }

    private val evictLock = Any()

    /** Ужимает хранилище до объёма, если объём уменьшили или место на диске съел кто-то другой. */
    fun evict(): Int = synchronized(evictLock) {
        val snap = current()
        var used = snap.bytes
        var usable = snap.usable
        if (used <= budgetFor(used, usable)) return 0
        var removed = 0
        for ((key, entry) in victims(snap)) {
            if (used <= budgetFor(used, usable)) break
            if (cache.delete(entry.file) || !entry.file.exists()) {
                noteRemoved(key)
                used -= entry.bytes
                usable += entry.bytes
                removed++
                onEvicted(key)
            }
        }
        removed
    }

    /** Кого вытеснять первым: видео, затем самое давнее по показу. Всё из памяти, без обращений к диску. */
    private fun victims(snap: Snapshot): List<Pair<String, Entry>> =
        listed(snap).sortedWith(compareBy({ kindOf(it.first) != Kind.VIDEO }, { it.second.usedAtMillis }))

    private fun reserve(): Long = (capacity() as? Capacity.ByFree)?.reserveBytes ?: 0L

    enum class Kind { PHOTO, VIDEO }

    private fun keyOf(file: File): String = file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun kindOf(key: String): Kind = if (key.startsWith("$VIDEOS/")) Kind.VIDEO else Kind.PHOTO

    companion object {
        const val PREVIEWS = "previews"
        const val VIDEOS = "videos"

        /** Сколько освобождать сверх нужного: двадцатая часть объёма, но не больше полугигабайта. */
        const val HEADROOM_SHARE = 20
        const val HEADROOM_MAX_BYTES = 512L * 1024 * 1024

        /** Как часто обновлять отметку давности на диске. */
        const val TOUCH_EVERY_MILLIS = 24L * 60 * 60 * 1000
    }
}
