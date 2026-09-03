package ru.dvedev.me.yaphotoframe.slideshow

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
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
    private val nextItem: suspend () -> MediaItem?,
    /** Куда вернуться при листании назад; null — истории нет. */
    private val previousItem: suspend () -> MediaItem? = { null },
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
    /** Кадр, не требующий сети, когда очередь не открывается; null — нет такого. */
    private val fallbackItem: suspend () -> MediaItem? = { null },
    /** Сюда сообщается о каждом пропущенном кадре — владелец увидит причину. */
    private val onSkip: (MediaItem, Exception) -> Unit = { _, _ -> },
    /** Подготовка кадра повисла — владелец увидит, где именно. */
    private val onStuck: () -> Unit = {},
) {

    private val requested = AtomicInteger(0)

    /** Пауза: кадр висит, пока не снимут; листание пультом работает и на паузе. */
    @Volatile
    var paused = false

    /**
     * Подготовленное, но не показанное.
     *
     * Появляется, когда к вертикальному снимку искали пару, а нашёлся
     * горизонтальный: выбрасывать его было бы расточительно, он станет
     * следующим кадром.
     */
    private var carried: PreparedItem? = null

    suspend fun run(scope: CoroutineScope) {
        var pending = awaitNext(scope) ?: return
        var isFirst = !animateFirst

        while (scope.isActive) {
            onShow(pending, !isFirst)
            isFirst = false

            val preparing = scope.async { prepareNext() }
            waitOutShow(scope)

            val ahead = try {
                // Сторож: подготовка однажды повисла насовсем, и рамка стояла
                // на одном снимке. Лучше пропустить кадр и записать, на чём
                // висели, чем молчать.
                withTimeoutOrNull(PREPARE_TIMEOUT_MILLIS) { preparing.await() } ?: run {
                    if (preparing.isActive) {
                        Log.w(TAG, "подготовка следующего кадра висит дольше минуты, пропускаю")
                        onStuck()
                        preparing.cancel()
                    }
                    null
                }
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
                ahead ?: awaitNext(scope) ?: return
            }
        }
    }

    /**
     * Ждёт, пока хоть что-нибудь удастся подготовить.
     *
     * Сдаваться нельзя: рамка однажды простояла ночь чёрной, потому что серия
     * отказов пришлась на пропавшую сеть, и цикл показа вышел насовсем. Сеть
     * возвращается, ссылки обновляются — надо просто попробовать позже.
     * Возвращает null только когда показ остановили.
     */
    private suspend fun awaitNext(scope: CoroutineScope): PreparedItem? {
        while (scope.isActive) {
            prepareNext()?.let { return it }
            Log.w(TAG, "показывать нечего, попробую через ${RETRY_MILLIS / 1000} с")
            delay(RETRY_MILLIS)
        }
        return null
    }

    /**
     * Держит кадр на экране положенное время, пересматривая срок на ходу.
     *
     * Одним долгим ожиданием обойтись нельзя: интервал крутят ползунком, и
     * уменьшение с минуты до пяти секунд должно сказаться сразу, а не после
     * того, как истечёт уже начатая минута.
     */
    private suspend fun waitOutShow(scope: CoroutineScope) {
        var startedAt = System.currentTimeMillis()
        while (scope.isActive) {
            if (requested.get() != 0) return
            if (paused) {
                // Время на паузе не идёт: отсчёт сдвигается вместе с ней.
                delay(POLL_INTERVAL_MILLIS)
                startedAt += POLL_INTERVAL_MILLIS
                continue
            }
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
                onSkip(item, e)
            }
        }
        Log.w(TAG, "подряд не удалось загрузить $MAX_ATTEMPTS кадров")

        // Череда отказов — это, скорее всего, сеть. Кадр из кэша сети не просит.
        val fallback = fallbackItem() ?: return null
        return try {
            Log.i(TAG, "беру из кэша ${fallback.name}")
            preparer.prepare(fallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "и из кэша не вышло ${fallback.name}: ${e.message}")
            onSkip(fallback, e)
            null
        }
    }

    private companion object {
        const val TAG = "YaPhotoFrame"

        /** Как часто пересматривать оставшееся время показа. */
        const val POLL_INTERVAL_MILLIS = 250L

        /** Сколько неудач подряд считать бедой сети, а не отдельного файла. */
        const val MAX_ATTEMPTS = 8

        /** Пауза между заходами, когда подготовить не удалось ничего. */
        const val RETRY_MILLIS = 10_000L

        /** Дольше этого кадр не готовится никогда; если готовится — он повис. */
        const val PREPARE_TIMEOUT_MILLIS = 60_000L
    }
}
