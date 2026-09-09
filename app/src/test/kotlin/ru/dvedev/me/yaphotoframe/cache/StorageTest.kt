package ru.dvedev.me.yaphotoframe.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.dvedev.me.yaphotoframe.media.PreviewSize
import java.io.File

/**
 * Хранилище одно на снимки и видео. Объём — либо бегунок, либо свободное
 * место минус запас; когда места нет, вытесняется самое старое по показу,
 * видео раньше снимков.
 */
class StorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_000_000L
    private var usable = 10_000L
    private lateinit var root: File

    private fun storage(capacity: Storage.Capacity) = Storage(
        root = root, place = Storage.Place.TvMemory, capacity = { capacity },
        clock = { now }, usableSpace = { usable }, totalSpace = { 100_000L },
    )

    private fun Storage.write(key: String, bytes: Int) {
        put(key) { it.writeBytes(ByteArray(bytes)) }
        now += 1000
    }

    @org.junit.Before
    fun setUp() {
        root = temporaryFolder.newFolder("storage")
    }

    @Test
    fun `снимки и видео лежат в своих ветках и считаются по отдельности`() {
        val s = storage(Storage.Capacity.Fixed(10_000L))
        s.write(s.previewKey("/a/1.jpg", PreviewSize.FULL), 100)
        s.write(s.previewKey("/a/1.jpg", PreviewSize.MICRO), 10)
        s.write(s.videoKey("/Видео/лето/v.mov"), 1000)

        assertEquals(2, s.count(Storage.Kind.PHOTO))
        assertEquals(1, s.count(Storage.Kind.VIDEO))
        assertEquals(1110L, s.usedBytes())
        assertTrue("видео лежит деревом как на Диске", File(root, "videos/Видео/лето/v.mov").isFile)
        assertEquals(listOf("/Видео/лето/v.mov"), s.videoPaths())
    }

    @Test
    fun `объём по бегунку не больше того, что есть на диске`() {
        usable = 500L
        val s = storage(Storage.Capacity.Fixed(10_000L))
        assertEquals(500L, s.budgetBytes())
        s.write(s.videoKey("/v.mov"), 200)
        usable = 300L
        assertEquals("занятое плюс свободное", 500L, s.budgetBytes())
    }

    @Test
    fun `объём по свободному месту — свободное минус запас плюс своё`() {
        usable = 5_000L
        val s = storage(Storage.Capacity.ByFree(1_000L))
        assertEquals(4_000L, s.budgetBytes())
        s.write(s.videoKey("/v.mov"), 1_000)
        usable = 4_000L
        assertEquals(4_000L, s.budgetBytes())
        assertTrue(s.fits(4_000L))
        assertFalse(s.fits(4_001L))
    }

    @Test
    fun `освобождая место, вытесняет сначала видео, потом самые старые снимки`() {
        val s = storage(Storage.Capacity.Fixed(1_000L))
        s.write(s.previewKey("/старый.jpg", PreviewSize.FULL), 200)   // самый старый
        s.write(s.videoKey("/v1.mov"), 300)
        s.write(s.previewKey("/новый.jpg", PreviewSize.FULL), 200)
        s.write(s.videoKey("/v2.mov"), 300)                          // самое свежее видео
        assertEquals(1_000L, s.usedBytes())

        assertTrue(s.makeRoom(400L))

        assertFalse("старое видео ушло первым", s.has(s.videoKey("/v1.mov")))
        assertFalse("потом второе видео", s.has(s.videoKey("/v2.mov")))
        assertTrue("снимки на месте", s.has(s.previewKey("/старый.jpg", PreviewSize.FULL)))
        assertTrue(s.has(s.previewKey("/новый.jpg", PreviewSize.FULL)))
    }

    @Test
    fun `снимки вытесняются, только когда видео уже нет, и самые старые первыми`() {
        val s = storage(Storage.Capacity.Fixed(500L))
        s.write(s.previewKey("/старый.jpg", PreviewSize.FULL), 200)
        s.write(s.previewKey("/новый.jpg", PreviewSize.FULL), 200)

        assertTrue(s.makeRoom(250L))

        assertFalse(s.has(s.previewKey("/старый.jpg", PreviewSize.FULL)))
        assertTrue(s.has(s.previewKey("/новый.jpg", PreviewSize.FULL)))
    }

    @Test
    fun `файл больше объёма не помещается никогда`() {
        val s = storage(Storage.Capacity.Fixed(1_000L))
        s.write(s.videoKey("/v.mov"), 300)
        assertFalse(s.makeRoom(1_001L))
        assertTrue("ничего не тронуто зря", s.has(s.videoKey("/v.mov")))
    }

    @Test
    fun `когда место на диске съел кто-то другой, evict ужимает своё`() {
        usable = 5_000L
        val s = storage(Storage.Capacity.ByFree(1_000L))
        s.write(s.videoKey("/v1.mov"), 2_000)
        s.write(s.videoKey("/v2.mov"), 2_000)
        assertEquals("список прочитан, пока места хватало", 4_000L, s.usedBytes())
        usable = 0L  // диск заполнили снаружи: объём = 4000 − 1000 = 3000
        // Список файлов и свободное место держатся в памяти и перечитываются
        // по просьбе, но не чаще раза в несколько минут.
        s.refresh()
        assertEquals("рано — не перечитано", 0, s.evict())
        now += Storage.REFRESH_INTERVAL_MILLIS
        s.refresh()
        assertEquals(1, s.evict())
        assertEquals(2_000L, s.usedBytes())
    }

    @Test
    fun `забыть файл — убрать и копии, и видео`() {
        val s = storage(Storage.Capacity.Fixed(10_000L))
        s.write(s.previewKey("/x.jpg", PreviewSize.FULL), 10)
        s.write(s.previewKey("/x.jpg", PreviewSize.MICRO), 10)
        s.write(s.videoKey("/x.jpg"), 10)
        s.forget("/x.jpg")
        assertEquals(0L, s.usedBytes())
    }
}
