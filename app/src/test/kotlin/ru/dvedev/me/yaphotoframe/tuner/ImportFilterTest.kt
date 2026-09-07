package ru.dvedev.me.yaphotoframe.tuner

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFilterTest {

    private val file = mapOf("folderUrl" to "https://disk.yandex.ru/d/x", "storageVolumeUuid" to "AAAA-1111", "showClock" to "true")

    @Test
    fun `чужая флешка из файла не переносится`() {
        val result = ImportFilter.filter(file) { false }
        assertEquals(file - "storageVolumeUuid", result)
    }

    @Test
    fun `своя флешка переносится`() {
        assertEquals(file, ImportFilter.filter(file) { it == "AAAA-1111" })
    }

    @Test
    fun `пустой выбор переносится как есть`() {
        val noFlash = file + ("storageVolumeUuid" to "")
        assertEquals(noFlash, ImportFilter.filter(noFlash) { false })
    }

    @Test
    fun `старое имя ключа переезжает под новое`() {
        val old = mapOf("folderUrl" to "x", "externalStorageUuid" to "AAAA-1111")
        assertEquals(mapOf("folderUrl" to "x", "storageVolumeUuid" to "AAAA-1111"), ImportFilter.filter(old) { true })
        assertEquals(mapOf("folderUrl" to "x"), ImportFilter.filter(old) { false })
    }
}
