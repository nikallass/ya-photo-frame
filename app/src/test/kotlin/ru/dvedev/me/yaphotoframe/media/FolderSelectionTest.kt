package ru.dvedev.me.yaphotoframe.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор папок — чистая арифметика над путями, и ошибиться в ней легко:
 * выбранная папка не подходит под собственный префикс со слэшем, и без
 * отдельной проверки обход не заходил внутрь того единственного, что просили
 * показать. Проверено на живых данных: библиотека схлопывалась в ноль.
 */
class FolderSelectionTest {

    @Test
    fun `пустой отбор пропускает всё`() {
        val all = FolderSelection.ALL

        assertTrue(all.includes("/любой/путь.jpg"))
        assertTrue(all.shouldDescend("/любая"))
    }

    @Test
    fun `обход заходит в саму выбранную папку`() {
        val only = FolderSelection.of(listOf("/Отпуск"))

        assertTrue("иначе внутрь выбранного не попасть", only.shouldDescend("/Отпуск"))
    }

    @Test
    fun `выбранная папка включает и всё вложенное`() {
        val only = FolderSelection.of(listOf("/Отпуск"))

        assertTrue(only.includes("/Отпуск/море.jpg"))
        assertTrue(only.includes("/Отпуск/2021/море.jpg"))
        assertTrue(only.shouldDescend("/Отпуск/2021"))
    }

    @Test
    fun `невыбранное остаётся снаружи`() {
        val only = FolderSelection.of(listOf("/Отпуск"))

        assertFalse(only.includes("/Дети/сад.jpg"))
        assertFalse(only.includes("/корень.jpg"))
        assertFalse(only.shouldDescend("/Дети"))
    }

    @Test
    fun `сквозь папку-предка обход проходит, но её файлы не берёт`() {
        val deep = FolderSelection.of(listOf("/Дети/2019"))

        assertTrue("иначе до цели не добраться", deep.shouldDescend("/Дети"))
        assertFalse("но сами по себе файлы предка не нужны", deep.includes("/Дети/битый.jpg"))
        assertTrue(deep.includes("/Дети/2019/сад.jpg"))
    }

    @Test
    fun `выбранный корень означает всю директорию`() {
        val root = FolderSelection.of(listOf("/"))

        assertTrue(root.includes("/корень.jpg"))
        assertTrue(root.includes("/Дети/сад.jpg"))
        assertTrue(root.shouldDescend("/что угодно"))
    }

    @Test
    fun `похожее имя не считается вложенным`() {
        val only = FolderSelection.of(listOf("/Отпуск"))

        assertFalse("«Отпуск 2021» — другая папка", only.includes("/Отпуск 2021/море.jpg"))
        assertFalse(only.shouldDescend("/Отпуск 2021"))
    }
}
