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
 */
class Storage(
    val root: File,
    val place: Place,
    private val capacity: () -> Capacity,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Свободное и полное место на диске хранилища — подменяются в тестах. */
    private val usableSpace: () -> Long = { root.usableSpace },
    private val totalSpace: () -> Long = { root.totalSpace },
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

    /** Что лежит: ключ → файл, суммарный размер, свободное место на момент чтения. */
    private class Snapshot(
        val files: MutableMap<String, File>,
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
            val fresh = read()
            synchronized(this) { snapshot = fresh }
            return fresh
        }
    }

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
        val files = scanned.associateByTo(linkedMapOf(), { keyOf(it.file) }, { it.file })
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
        val snap = snapshot ?: return
        val size = file.length()
        snap.files.put(key, file)?.let { snap.bytes -= it.length() }
        snap.bytes += size
        snap.usable = (snap.usable - size).coerceAtLeast(0L)
    }

    @Synchronized
    private fun noteRemoved(key: String, size: Long) {
        val snap = snapshot ?: return
        if (snap.files.remove(key) != null) {
            snap.bytes -= size
            snap.usable += size
        }
    }

    @Synchronized
    private fun forgetSnapshot() {
        snapshot = null
    }

    /** Копия списка под сортировку — сам список правится под замком. */
    @Synchronized
    private fun listed(snap: Snapshot): List<Pair<String, File>> = snap.files.entries.map { it.key to it.value }

    // ── ключи ──

    fun previewKey(item: MediaItem, size: PreviewSize): String = previewKey(item.path, size)

    fun previewKey(path: String, size: PreviewSize): String = PREVIEWS + "/" + CacheKey.forPreview(path, size)

    fun videoKey(path: String): String = VIDEOS + "/" + path.trimStart('/')

    // ── файлы ──

    /** Пока список ещё не прочитан, спрашиваем у диска напрямую — это один stat, а не обход. */
    fun has(key: String): Boolean {
        val snap = synchronized(this) { snapshot } ?: return cache.has(key)
        return synchronized(this) { snap.files.containsKey(key) }
    }

    fun file(key: String): File {
        val file = cache.file(key)
        if (!file.isFile) {
            // Удалили руками или флешку почистили: список об этом не знал.
            synchronized(this) { snapshot?.files?.remove(key) }
            return file
        }
        cache.touch(key)
        return file
    }

    fun put(key: String, write: (File) -> Unit): File {
        val file = cache.put(key, write)
        noteAdded(key, file)
        return file
    }

    fun remove(key: String): Boolean {
        val size = cache.file(key).length()
        val removed = cache.remove(key)
        if (removed) noteRemoved(key, size)
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
        listed(current()).filter { kindOf(it.first) == kind }.sumOf { it.second.length() }

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
     */
    fun makeRoom(bytes: Long): Boolean {
        if (!fits(bytes)) return false
        // До первого чтения списка вытеснять нечего и незачем: место есть.
        val snap = peek() ?: return usableSpace() >= bytes + reserve()
        var used = snap.bytes
        var usable = snap.usable
        if (roomFor(bytes, used, usable)) return true
        for ((key, file) in victims(snap)) {
            if (roomFor(bytes, used, usable)) break
            val size = file.length()
            if (cache.delete(file)) {
                noteRemoved(key, size)
                used -= size
                usable += size
            }
        }
        return roomFor(bytes, used, usable)
    }

    /** Ужимает хранилище до объёма, если объём уменьшили или место на диске съел кто-то другой. */
    fun evict(): Int {
        val snap = current()
        var used = snap.bytes
        var usable = snap.usable
        if (used <= budgetFor(used, usable)) return 0
        var removed = 0
        for ((key, file) in victims(snap)) {
            if (used <= budgetFor(used, usable)) break
            val size = file.length()
            if (cache.delete(file)) {
                noteRemoved(key, size)
                used -= size
                usable += size
                removed++
            }
        }
        return removed
    }

    /** Кого вытеснять первым: видео, затем самое давнее по показу. */
    private fun victims(snap: Snapshot): List<Pair<String, File>> =
        listed(snap).sortedWith(compareBy({ kindOf(it.first) != Kind.VIDEO }, { it.second.lastModified() }))

    private fun reserve(): Long = (capacity() as? Capacity.ByFree)?.reserveBytes ?: 0L

    enum class Kind { PHOTO, VIDEO }

    private fun keyOf(file: File): String = file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun kindOf(key: String): Kind = if (key.startsWith("$VIDEOS/")) Kind.VIDEO else Kind.PHOTO

    companion object {
        const val PREVIEWS = "previews"
        const val VIDEOS = "videos"

    }
}
