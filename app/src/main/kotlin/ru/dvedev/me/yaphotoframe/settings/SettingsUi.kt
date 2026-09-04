package ru.dvedev.me.yaphotoframe.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Тексты и порядок настроек — один файл на страницу и на экран в приложении.
 *
 * Раньше заголовки и подсказки жили в двух списках, и они разъезжались: на
 * телевизоре «Длина хода», на телефоне «Путь кадра». Теперь источник один,
 * лежит в ресурсах, страница берёт его по HTTP, приложение — из assets, а
 * тест следит, что у каждого ключа настроек есть описание и что тексты
 * соблюдают правила: короткий заголовок, одна строка подсказки, длинное — в
 * справку.
 */
@Serializable
data class SettingsUi(
    val sections: List<Section>,
    /** Строки, которых на странице нет — только на экране в приложении. */
    val app: List<Item> = emptyList(),
    val tabs: Map<String, Block> = emptyMap(),
    val blocks: List<Block> = emptyList(),
    val buttons: Map<String, Block> = emptyMap(),
) {
    @Serializable
    data class Section(
        val id: String,
        val title: String,
        val note: String,
        val help: String? = null,
        val items: List<Item> = emptyList(),
    )

    @Serializable
    data class Item(
        val key: String,
        /** `slider`, `toggle`, `folder`, `volume` — как строку рисовать. */
        val kind: String,
        val title: String,
        val note: String,
        val help: String? = null,
    )

    /** Блок вкладки или кнопка: заголовок, строка под ним, справка. */
    @Serializable
    data class Block(
        val id: String = "",
        val title: String,
        val note: String = "",
        val help: String? = null,
    )

    /** Все строки настроек по порядку: секции, затем то, что есть только в приложении. */
    fun items(): List<Item> = sections.flatMap { it.items } + app

    fun item(key: String): Item? = items().firstOrNull { it.key == key }

    companion object {
        const val ASSET = "settings-ui.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): SettingsUi = json.decodeFromString(serializer(), text)
    }
}
