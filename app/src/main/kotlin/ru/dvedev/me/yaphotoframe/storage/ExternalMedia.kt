package ru.dvedev.me.yaphotoframe.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

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
        /** Папка рамки на томе. */
        val root: File,
        val totalBytes: Long,
        val freeBytes: Long,
    )

    fun volumes(): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        // Сама система заводит папку приложения на каждом примонтированном томе.
        val dirs = runCatching { context.externalMediaDirs }.getOrNull().orEmpty().filterNotNull()
        return dirs.mapNotNull { dir ->
            val volume = runCatching { manager.getStorageVolume(dir) }.getOrNull() ?: return@mapNotNull null
            if (!volume.isRemovable || volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val uuid = volume.uuid ?: return@mapNotNull null
            val stat = runCatching { StatFs(dir.path) }.getOrNull() ?: return@mapNotNull null
            Volume(
                uuid = uuid,
                label = volume.getDescription(context) ?: uuid,
                root = File(dir, FOLDER),
                totalBytes = stat.totalBytes,
                freeBytes = stat.availableBytes,
            )
        }
    }

    fun volume(uuid: String): Volume? = volumes().firstOrNull { it.uuid == uuid }

    companion object {
        /** Имя папки рамки на флешке — по-русски: её будут искать глазами. */
        const val FOLDER = "Фоторамка"
    }
}
