package ru.dvedev.me.yaphotoframe.media

/**
 * Какие папки внутри расшаренной директории показывать.
 *
 * Пустой набор означает «всю целиком» — так рамка ведёт себя из коробки, пока
 * владелец ничего не выбрал. Выбранная папка включает и всё, что в ней вложено:
 * отмечать каждую подпапку по отдельности было бы мучением.
 *
 * Отбор нужен не для красоты: владелец складывает в общую директорию всё
 * подряд и постепенно разбирает её, помечая разобранное. Показывать при этом
 * весь ворох целиком — значит показывать и то, что ещё не просмотрено.
 */
@JvmInline
value class FolderSelection(private val paths: Set<String>) {

    val isEmpty: Boolean get() = paths.isEmpty()

    val selected: Set<String> get() = paths

    /** Показывать ли файл по этому пути. */
    fun includes(filePath: String): Boolean {
        if (paths.isEmpty()) return true
        return paths.any { filePath.startsWith(prefixOf(it)) }
    }

    /**
     * Спускаться ли в эту папку при обходе.
     *
     * Спускаемся в двух случаях: папка сама выбрана (или лежит внутри
     * выбранной), либо она — предок чего-то выбранного, и пройти сквозь неё
     * нужно, чтобы добраться до цели.
     */
    fun shouldDescend(folderPath: String): Boolean {
        if (paths.isEmpty()) return true
        val here = prefixOf(folderPath)
        return paths.any {
            // Сама выбранная папка — отдельным случаем: под свой же префикс со
            // слэшем она не подходит, и без этой проверки обход не заходил
            // внутрь того единственного, что и просили показать.
            it == folderPath || folderPath.startsWith(prefixOf(it)) || it.startsWith(here)
        }
    }

    private fun prefixOf(path: String): String = if (path == ROOT) ROOT else "$path/"

    companion object {
        const val ROOT = "/"
        val ALL = FolderSelection(emptySet())

        fun of(paths: Collection<String>) = FolderSelection(paths.toSet())
    }
}
