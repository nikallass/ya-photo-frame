package ru.dvedev.me.yaphotoframe.cache

import java.io.File
import java.io.IOException

/**
 * Кэш показанного и подготовленного к показу.
 *
 * Правила одни и те же для фотографий и для видео. Это не аккуратность ради
 * аккуратности: отдельный механизм под фотографии пришлось бы сращивать с
 * механизмом под видео, когда до видео дойдут руки. Разница между ними
 * выражается одним числом — порогом, выше которого файл не кладут в кэш вовсе.
 *
 * Давность использования хранится во времени изменения самого файла, отдельного
 * учёта нет: лишний индекс здесь пришлось бы держать в согласии с содержимым
 * директории, а он не даёт ничего сверх того, что и так знает файловая система.
 */
class MediaCache(
    private val directory: File,
    private val budgetBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        directory.mkdirs()
    }

    fun has(key: String): Boolean = file(key).isFile

    fun file(key: String): File = File(directory, key)

    /** Отмечает файл использованным — по этой отметке и вытесняется. */
    fun touch(key: String) {
        val file = file(key)
        if (file.isFile) file.setLastModified(clock())
    }

    /**
     * Кладёт содержимое в кэш.
     *
     * Пишется рядом и переименовывается: оборванная загрузка не должна оставить
     * половину файла под правильным именем — потом её приняли бы за готовую.
     */
    fun put(key: String, write: (File) -> Unit): File {
        val target = file(key)
        // Имя временного файла уникально на каждую попытку. С общим именем две
        // одновременные подготовки одного и того же кадра дрались за него:
        // первая переименовывала файл, вторая не находила его и падала.
        // Наблюдалось на устройстве — один снимок стабильно не попадал в кэш.
        val temporary = File(directory, "$key.${System.nanoTime()}.part")
        try {
            write(temporary)
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) throw IOException("не удалось положить $key в кэш")
        } finally {
            temporary.delete()
        }
        target.setLastModified(clock())
        return target
    }

    /** Убирает файл из кэша. Возвращает, был ли он там вообще. */
    fun remove(key: String): Boolean {
        val file = file(key)
        return file.isFile && file.delete()
    }

    fun totalBytes(): Long = entries().sumOf { it.length() }

    fun count(): Int = entries().size

    /**
     * Освобождает место под бюджет, начиная с того, к чему дольше всего не
     * обращались. Возвращает, сколько файлов удалено.
     */
    fun evict(): Int {
        var total = totalBytes()
        val budget = budgetBytes()
        if (total <= budget) return 0

        var removed = 0
        for (file in entries().sortedBy { it.lastModified() }) {
            if (total <= budget) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
            }
        }
        return removed
    }

    fun clear() {
        entries().forEach { it.delete() }
    }

    private fun entries(): List<File> =
        directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()

    /** Прибирает недописанное, оставшееся от прерванных загрузок. */
    fun sweepLeftovers() {
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }
}
