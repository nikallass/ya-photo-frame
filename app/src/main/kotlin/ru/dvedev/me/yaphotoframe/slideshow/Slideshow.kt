package ru.dvedev.me.yaphotoframe.slideshow

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.dvedev.me.yaphotoframe.media.MediaItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Бесконечная смена кадров.
 *
 * Порядок задаёт движок: слайдшоу лишь спрашивает следующий кадр, готовит его,
 * пока висит текущий, и выдерживает время показа. Без подготовки заранее на
 * каждой смене был бы виден провал в чёрное на время загрузки.
 */
class Slideshow(
    private val nextItem: () -> MediaItem?,
    /** Куда вернуться при листании назад; null — истории нет. */
    private val previousItem: () -> MediaItem? = { null },
    private val preparer: FramePreparer,
    private val showDurationMillis: () -> Long,
    /** Сводить ли вертикальные снимки в пары. */
    private val pairPortraits: () -> Boolean = { false },
    private val onShow: (PreparedItem, Boolean) -> Unit,
    /**
     * Проявлять ли самый первый кадр.
     *
     * Обычно первый кадр возникает на чёрном и проявлять его не из чего. Но при
     * холодном старте на экране уже висит случайная находка, показанная до
     * обхода, и резкая подмена бросалась бы в глаза.
     */
    private val animateFirst: Boolean = false,
) {

    private val requested = AtomicInteger(0)

    /**
     * Подготовленное, но не показанное.
     *
     * Появляется, когда к вертикальному снимку искали пару, а нашёлся
     * горизонтальный: выбрасывать его было бы расточительно, он станет
     * следующим кадром.
     */
    private var carried: PreparedItem? = null

    suspend fun run(scope: CoroutineScope) {
        var pending = prepareNext() ?: run {
            Log.w(TAG, "показывать нечего")
            return
        }
        var isFirst = !animateFirst

        while (scope.isActive) {
            onShow(pending, !isFirst)
            isFirst = false

            val preparing = scope.async { prepareNext() }
            waitOutShow(scope)

            val ahead = try {
                preparing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "не удалось подготовить следующий кадр", e)
                null
            }

            // Кадр вперёд уже готов; если попросили назад, он не пропадает
            // впустую, а честно освобождается — иначе на каждом листании
            // назад в нативной памяти оставалось бы по пять мегабайт.
            val back = if (requested.getAndSet(0) < 0) prepareBack() else null
            pending = if (back != null) {
                ahead?.discard()
                back
            } else {
                ahead ?: break
            }
        }
    }

    /**
     * Держит кадр на экране положенное время, пересматривая срок на ходу.
     *
     * Одним долгим ожиданием обойтись нельзя: интервал крутят ползунком, и
     * уменьшение с минуты до пяти секунд должно сказаться сразу, а не после
     * того, как истечёт уже начатая минута.
     */
    private suspend fun waitOutShow(scope: CoroutineScope) {
        val startedAt = System.currentTimeMillis()
        while (scope.isActive) {
            if (requested.get() != 0) return
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = showDurationMillis() - elapsed
            if (remaining <= 0) return
            delay(minOf(remaining, POLL_INTERVAL_MILLIS))
        }
    }

    /**
     * Просьба перелистнуть: 1 — вперёд, -1 — назад.
     *
     * Только отмечает намерение; выполнит его цикл показа. Пришло это из
     * обработчика клавиш, то есть с главного потока, а цикл живёт в своём — их
     * связывает одно атомарное число, и большего здесь не нужно.
     */
    fun page(direction: Int) {
        requested.set(direction)
    }

    private suspend fun prepareBack(): PreparedItem? {
        val item = previousItem() ?: return null
        return try {
            preparer.prepare(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "не удалось вернуться к ${item.name}", e)
            null
        }
    }

    /**
     * Готовит ближайшее, что удалось загрузить.
     *
     * Один недоступный файл не должен останавливать рамку, поэтому неудачи
     * пропускаются — но не бесконечно: череда отказов подряд означает, что дело
     * не в отдельном файле, а в сети или в хранилище.
     */
    private suspend fun prepareNext(): PreparedItem? {
        carried?.let { carried = null; return it }

        val first = prepareOne() ?: return null
        if (first !is PreparedPhoto) return first
        if (!first.isPortrait) return first
        if (!pairPortraits()) {
            Log.d(TAG, "пары выключены, ${first.item.name} идёт один")
            return first
        }
        Log.d(TAG, "ищу пару к ${first.item.name}")

        // Вертикальному снимку ищем пару — но только среди тех, кто и так
        // следующий в очереди. Специально перебирать библиотеку в поисках
        // второго вертикального значило бы ломать порядок показа.
        val second = prepareOne()
        return when {
            second == null -> first
            second is PreparedPhoto && second.isPortrait -> {
                Log.d(TAG, "пара: ${first.item.name} + ${second.item.name}")
                preparer.pair(first, second)
            }

            else -> {
                Log.d(TAG, "пары не вышло: ${second.item.name} не вертикальный")
                carried = second
                first
            }
        }
    }

    private suspend fun prepareOne(): PreparedItem? {
        repeat(MAX_ATTEMPTS) {
            val item = nextItem() ?: return null
            try {
                return preparer.prepare(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "пропускаю ${item.name}: ${e.message}")
            }
        }
        Log.w(TAG, "подряд не удалось загрузить $MAX_ATTEMPTS кадров")
        return null
    }

    private companion object {
        const val TAG = "YaPhotoFrame"

        /** Как часто пересматривать оставшееся время показа. */
        const val POLL_INTERVAL_MILLIS = 250L

        /** Сколько неудач подряд считать бедой сети, а не отдельного файла. */
        const val MAX_ATTEMPTS = 8
    }
}
