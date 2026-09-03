package ru.dvedev.me.yaphotoframe.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_000L

    @Test
    fun `ключ с папками ложится деревом, а удаление прибирает пустые папки`() {
        val root = temporaryFolder.newFolder()
        val cache = MediaCache(root, { Long.MAX_VALUE }, { now })

        cache.put("Отпуск/2024/море.mov") { it.writeBytes(ByteArray(10)) }

        assertTrue(File(root, "Отпуск/2024/море.mov").isFile)
        assertEquals(listOf("Отпуск/2024/море.mov"), cache.keys())
        assertEquals(1, cache.count())

        assertTrue(cache.remove("Отпуск/2024/море.mov"))
        assertFalse("пустые папки убраны", File(root, "Отпуск").exists())
        assertTrue("сам корень на месте", root.isDirectory)
    }

    @Test
    fun `бюджет от свободного места оставляет запас`() {
        val root = temporaryFolder.newFolder()
        // Свободно ровно столько, сколько запас: бюджет равен занятому.
        val cache = MediaCache(root, MediaCache.reserveBudget(root, { 1000 }, { 1000 }), { now })

        cache.put("a/старый.mov") { it.writeBytes(ByteArray(100)) }
        now += 10
        cache.put("b/новый.mov") { it.writeBytes(ByteArray(100)) }

        // Занято 200, бюджет = 200 + свободно − свободно = 200: в бюджете.
        assertEquals(0, cache.evict())

        // Запас вырос на 100 байт — старому пора на выход.
        val squeezed = MediaCache(root, MediaCache.reserveBudget(root, { 1100 }, { 1000 }), { now })
        assertEquals(1, squeezed.evict())
        assertFalse(File(root, "a/старый.mov").exists())
        assertTrue(File(root, "b/новый.mov").exists())
    }
}
