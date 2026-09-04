package ru.dvedev.me.yaphotoframe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dvedev.me.yaphotoframe.ui.FrameSettings
import java.io.File

/**
 * Тексты настроек лежат в одном файле для страницы и приложения. Правила
 * из спецификации 1.2 держатся тестом, а не памятью: короткий заголовок, одна
 * строка подсказки, длинное — в справку, «кэш» и «флешка» вместо «кеш» и
 * «носитель». У каждого ключа настроек ровно одно описание.
 */
class SettingsUiTest {

    private val ui = SettingsUi.parse(
        File("src/main/assets/" + SettingsUi.ASSET).readText(Charsets.UTF_8),
    )

    /** Что на странице и в приложении не крутится: отбор папок живёт на своей вкладке. */
    private val notOnScreen = setOf("selectedFolders")

    @Test
    fun `у каждого ключа настроек ровно одно описание`() {
        val keys = FrameSettings().asMap().keys - notOnScreen
        val described = ui.items().groupingBy { it.key }.eachCount()

        assertEquals("нет описания", emptySet<String>(), keys - described.keys)
        assertEquals("лишние ключи", emptySet<String>(), described.keys - keys)
        assertTrue("дубли: " + described.filterValues { it > 1 }, described.values.all { it == 1 })
    }

    @Test
    fun `заголовки короткие, строки под ними в одну строку`() {
        val problems = mutableListOf<String>()
        for (item in ui.items()) {
            if (item.title.isBlank()) problems += item.key + ": пустой заголовок"
            if (item.title.split(' ').size > 4) problems += item.key + ": заголовок длиннее четырёх слов"
            if (item.note.length > 70) problems += item.key + ": строка длиннее 70 знаков"
            if ('\n' in item.note) problems += item.key + ": перенос в строке"
        }
        for (section in ui.sections) {
            if (section.id != "folder" && section.title.isBlank()) problems += section.id + ": раздел без заголовка"
            if (section.note.length > 100) problems += section.id + ": подзаголовок длиннее 100 знаков"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `в текстах нет «носитель» и «кеш»`() {
        val texts = ui.items().flatMap { listOf(it.title, it.note, it.help.orEmpty()) } +
            ui.sections.flatMap { listOf(it.title, it.note, it.help.orEmpty()) } +
            (ui.tabs.values + ui.blocks + ui.buttons.values).flatMap { listOf(it.title, it.note, it.help.orEmpty()) }
        val offenders = texts.filter { text ->
            val lower = text.lowercase()
            "носител" in lower || "кеш" in lower
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `виды строк известны странице`() {
        val kinds = setOf("slider", "toggle", "folder", "volume")
        assertEquals(emptyList<String>(), ui.items().filter { it.kind !in kinds }.map { it.key })
    }

    @Test
    fun `разделы идут в утверждённом порядке`() {
        assertEquals(
            listOf("folder", "transitions", "motion", "background", "content", "video", "flash", "memory", "transfer"),
            ui.sections.map { it.id },
        )
    }
}
