package ru.dvedev.me.yaphotoframe.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import java.io.IOException

/**
 * Съёмные тома, куда рамке можно писать без разрешений.
 *
 * На этих телевизорах нет ни экрана «доступ ко всем файлам», ни системного
 * выбора папки, так что единственное место на флешке, куда приложение
 * пишет само, — его собственная папка `Android/media/<пакет>`. Файлы там
 * обычные: флешку можно вынуть и открыть на компьютере или в штатном
 * проигрывателе телевизора.
 *
 * Том помнится по UUID: путь `/storage/XXXX-XXXX` от него и образуется, а
 * подключённая заново флешка получает тот же.
 */
class ExternalMedia(private val context: Context) {

    data class Volume(
        val uuid: String,
        val label: String,
        /** Папка рамки на томе; null — тома нет или писать некуда. */
        val root: File?,
        val totalBytes: Long,
        val freeBytes: Long,
        /** Почему на том нельзя положиться; null — всё в порядке. */
        val problem: String?,
    ) {
        val usable: Boolean get() = problem == null && root != null
    }

    fun volumes(): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        // Сама система заводит папку приложения на каждом примонтированном томе.
        val dirs = runCatching { context.externalMediaDirs }.getOrNull().orEmpty().filterNotNull()
            .mapNotNull { dir ->
                runCatching { manager.getStorageVolume(dir)?.uuid }.getOrNull()?.let { it to dir }
            }.toMap()
        return runCatching { manager.storageVolumes }.getOrDefault(emptyList())
            .filter { it.isRemovable && it.uuid != null }
            .map { volume -> describe(volume, dirs[volume.uuid]) }
    }

    fun volume(uuid: String): Volume? = volumes().firstOrNull { it.uuid == uuid }

    private fun describe(volume: StorageVolume, dir: File?): Volume {
        val uuid = volume.uuid.orEmpty()
        val label = volume.getDescription(context) ?: uuid
        val state = volume.state
        val stat = dir?.let { runCatching { StatFs(it.path) }.getOrNull() }
        val root = dir?.let { File(it, FOLDER) }
        val problem = when {
            state == Environment.MEDIA_UNMOUNTABLE ->
                "файловая система не поддерживается — нужна exFAT, NTFS или FAT32"
            state == Environment.MEDIA_MOUNTED_READ_ONLY -> "только чтение — записать ничего не получится"
            state == Environment.MEDIA_CHECKING -> "подключается, подождите"
            state != Environment.MEDIA_MOUNTED -> "не подключён ($state)"
            root == null -> "система не дала рамке папку на этом томе"
            else -> writeProbe(root)
        }
        return Volume(
            uuid = uuid,
            label = label,
            root = root,
            totalBytes = stat?.totalBytes ?: 0L,
            freeBytes = stat?.availableBytes ?: 0L,
            problem = problem,
        )
    }

    /**
     * Пробует записать в папку рамки; возвращает причину, если не вышло.
     *
     * Больше про том ничего не выясняется: тип файловой системы Android
     * приложению не отдаёт, а догадки по поведению на другом телевизоре
     * повели бы себя иначе. Файл слишком крупный для тома выяснится при
     * записи и уйдёт в дневник.
     */
    private fun writeProbe(root: File): String? {
        val probe = File(root, ".проба-${java.util.UUID.randomUUID().toString().take(8)}")
        return try {
            root.mkdirs()
            probe.writeBytes(byteArrayOf(1))
            null
        } catch (e: IOException) {
            "записать не удалось: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: SecurityException) {
            "записать не удалось: нет прав"
        } finally {
            probe.delete()
        }
    }

    companion object {
        /** Имя папки рамки на флешке — по-русски: её будут искать глазами. */
        const val FOLDER = "Фоторамка"
    }
}
