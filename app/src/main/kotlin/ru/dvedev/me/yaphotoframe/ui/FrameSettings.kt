package ru.dvedev.me.yaphotoframe.ui

import ru.dvedev.me.yaphotoframe.Defaults
import ru.dvedev.me.yaphotoframe.cache.CachePolicy

/**
 * Всё, чем задаётся поведение рамки.
 *
 * Собрано в одном месте не ради порядка: эти величины подбираются глазами на
 * телевизоре, десятками итераций, и должны меняться на лету. Здесь только
 * значения по умолчанию — ползунки появятся отдельным шагом.
 */
data class FrameSettings(
    /**
     * Публичная ссылка на папку Яндекс.Диска.
     *
     * Настройка, а не константа: чтобы сменить альбом, не нужно пересобирать
     * приложение. Значение по умолчанию зашито в сборку, чтобы рамка показывала
     * что-то сразу после установки.
     */
    val folderUrl: String = Defaults.PUBLIC_FOLDER_URL,

    /** Сколько висит один кадр. Двадцать секунд — как обжилось на телевизоре владельца; от 5 с до часа. */
    val showDurationMillis: Long = 20_000L,

    /** Длительность перехода между кадрами. */
    val crossfadeMillis: Long = 1_500L,

    /**
     * Путь кадра: насколько далеко он уезжает за показ, долей от ширины экрана.
     *
     * Ход растянут на всё время показа: кадр доходит до конца пути ровно к
     * смене. Отдельной скорости нет — она получалась из двух ручек, и с
     * умолчаниями кадр никогда не доезжал до заданного. Три процента — это
     * около шести сантиметров на 43 дюймах за двадцать секунд.
     */
    val driftAmplitude: Float = 0.03f,

    /**
     * Насколько кадр вырастает за показ, долей от своего размера.
     *
     * Медленное приближение — второе, после хода, движение, которое делает
     * рамку живой. Четыре процента за показ глазом почти не ловятся, но
     * замершим кадр уже не кажется. Ноль — без приближения.
     */
    val zoomAmount: Float = 0.04f,

    /** Доля экрана, внутри которой помещается кадр. */
    val frameInset: Float = 0.92f,

    /** Наименьший отступ кадра от края экрана, долей от стороны. */
    val edgeMargin: Float = 0.06f,

    /** Насколько уходить от центра к точке золотого сечения: 0 — центр, 1 — сама точка. */
    val placementStrength: Float = 0.5f,

    /** Затемнение фона: 0 — как есть, 1 — чёрный. */
    val backgroundDim: Float = 0.4f,

    /** До скольких пикселей ужимать фон. Меньше — размытее. */
    val blurSampleLongSide: Int = BackgroundBlur.DEFAULT_SAMPLE_LONG_SIDE,

    /**
     * Мельче какой доли экрана (по длинной стороне) снимок не показывать.
     *
     * Кадр выводится в натуральную величину, и превью из мессенджера или
     * мелкий кроп занимают на телевизоре ладонь. Четверть экрана отсекает
     * их, но не трогает обычные снимки: их копии с Диска — 1280 px, то есть
     * две трети Full HD. Ноль — показывать всё.
     */
    val minPhotoFraction: Float = 0.25f,

    /**
     * Поднимать ли страницу настройки в локальной сети.
     *
     * Задумывалась как инструмент разработки, но оказалась удобной и в
     * повседневной жизни: подкрутить рамку с телефона проще, чем искать пульт.
     * Поэтому включена по умолчанию, а не только в отладочной сборке. Выключить
     * можно там же — тогда телевизор перестанет слушать порт.
     */
    val tunerEnabled: Boolean = true,

    /** Сколько места отдано под кэш. */
    val cacheBudgetBytes: Long = CachePolicy.DEFAULT_BUDGET_BYTES,

    /** Выше какого размера файл не кладут в кэш, а проигрывают потоком. */
    val cacheItemThresholdBytes: Long = CachePolicy.DEFAULT_ITEM_THRESHOLD_BYTES,

    /**
     * Как часто переобходить папку.
     *
     * Обход десятка подпапок — это десятки запросов; при каждом включении
     * заставки он расточителен, а папка пополняется вручную и понемногу.
     *
     * Но и реже трёх часов нельзя: ссылки на превью, которые выдаёт Диск,
     * живут около трёх часов, а обход их обновляет. Полтора часа — с запасом.
     */
    val indexRefreshIntervalMillis: Long = 90L * 60 * 1000,

    /**
     * Какие папки внутри расшаренной директории показывать.
     *
     * Пусто — вся целиком. Отмеченная папка включает и всё вложенное.
     */
    val selectedFolders: Set<String> = emptySet(),

    /**
     * Показывать ли видео наравне с фотографиями.
     *
     * Индексируются они всегда — включение не требует переобхода хранилища.
     */
    val showVideo: Boolean = true,

    /**
     * Сколько держать ролик, если он длиннее.
     *
     * Домашняя съёмка бывает получасовой, а рамка — не кинозал: досмотреть
     * можно и в плеере. Ноль означает «до конца, сколько бы ни длился».
     */
    val videoMaxDurationMillis: Long = 120_000L,

    /**
     * Тяжелее скольких байт ролик не показывать; ноль — без ограничения.
     *
     * Ролик тяжелее порога кэша идёт потоком, а канал телевизора не тянет
     * битрейт съёмки с фотоаппарата: такой ролик заикается. Пережатых
     * вариантов Диск не отдаёт, так что совсем тяжёлые проще не брать вовсе.
     */
    val videoMaxSizeBytes: Long = 0L,

    /**
     * Сколько места отдано под подкачку потока заранее; ноль — не подкачивать.
     *
     * Целиком тяжёлый ролик на телевизор не кладётся — места нет. Но его
     * начало подкачивается в буфер этого объёма до показа, и на экран он
     * выходит, когда начало на месте; чего не хватило — доигрывает потоком.
     * Буфер общий и ограничен: череда роликов его не переполнит.
     */
    val streamBufferBytes: Long = 512L * 1024 * 1024,

    /**
     * Пропускная способность канала до Диска, бит/с; ноль — стримить всё.
     *
     * Ролик с битрейтом выше не идёт потоком: он бы заикался. Ему дорога
     * через флешка, а без флешки он пропускается. Замерено на месте:
     * телевизор берёт с Диска 8–12 МБ/с, то есть 64–96 Мбит/с; 40 — с
     * запасом на соседей по сети.
     */
    val streamMaxBitrateBps: Long = 40_000_000L,

    /**
     * UUID тома флешки под тяжёлые ролики; пусто — флешки нет.
     *
     * Ролик тяжелее канала качается на неё один раз и идёт с неё без
     * заиканий. Выбор явный: чужую флешку, воткнутую на минуту, засыпать
     * гигабайтами нельзя. Помнится по тому, а не по пути: путь меняется от
     * вставки к вставке.
     */
    val externalStorageUuid: String = "",

    /**
     * Сколько места на флешке не занимать.
     *
     * Занять можно всё свободное: флешка куплена под рамку, и число в
     * гигабайтах владельцу вписывать незачем. Гигабайт остаётся на случай,
     * если флешкой пользуются и для другого.
     */
    val externalReserveBytes: Long = 1024L * 1024 * 1024,

    /**
     * Со звуком ли.
     *
     * По умолчанию нет: заставка, внезапно заговорившая в тишине, пугает.
     */
    val videoSoundEnabled: Boolean = false,

    /**
     * Ставить ли два вертикальных снимка рядом.
     *
     * Вертикальный кадр занимает треть ширины экрана, и пара заполняет его
     * целиком. Пара берётся только из соседей по очереди — специально искать
     * второго не нужно.
     */
    val pairPortraits: Boolean = true,

    /**
     * Сколько дней снимок считается недавно добавленным.
     *
     * Всё это время он выпадает заметно чаще прочих. Две недели подходят, когда
     * папку пополняют регулярно; если снимки заливали одним заходом полгода
     * назад, окно стоит расширить — иначе бонусу не на что действовать.
     *
     * Свежим считается и то, что впервые попало в рамку, — например, из только
     * что отмеченной подпапки, — сколько бы давно оно ни лежало на Диске.
     */
    val freshnessWindowDays: Int = 14,

    /**
     * Через сколько пауза снимается сама; ноль — никогда.
     *
     * Пауза ставится «на минутку» и забывается, а рамка потом сутки висит на
     * одном кадре — с риском выжечь экран.
     */
    val pauseAutoResumeMillis: Long = 10L * 60 * 1000,

    /** Показывать ли часы поверх кадра. */
    val showClock: Boolean = true,

    /** Показывать ли дату съёмки поверх кадра. */
    val showDate: Boolean = true,

    /** На сколько кадров вперёд смотреть и что подгружать заранее. */
    val prefetchCount: Int = CachePolicy.DEFAULT_PREFETCH_COUNT,
) {
    /**
     * Все настройки по ключам JSON; порядок на странице задаёт settings-ui.json.
     *
     * Единственный перечень ключей: по нему сервер пишет JSON, а тест
     * сверяет, что у каждого ключа есть тексты на странице.
     */
    fun asMap(): Map<String, Any> = linkedMapOf(
        "folderUrl" to folderUrl,
        "showDurationMillis" to showDurationMillis,
        "crossfadeMillis" to crossfadeMillis,
        "driftAmplitude" to driftAmplitude,
        "zoomAmount" to zoomAmount,
        "frameInset" to frameInset,
        "edgeMargin" to edgeMargin,
        "placementStrength" to placementStrength,
        "backgroundDim" to backgroundDim,
        "blurSampleLongSide" to blurSampleLongSide,
        "tunerEnabled" to tunerEnabled,
        "cacheBudgetBytes" to cacheBudgetBytes,
        "cacheItemThresholdBytes" to cacheItemThresholdBytes,
        "prefetchCount" to prefetchCount,
        "indexRefreshIntervalMillis" to indexRefreshIntervalMillis,
        "showVideo" to showVideo,
        "videoMaxDurationMillis" to videoMaxDurationMillis,
        "videoSoundEnabled" to videoSoundEnabled,
        "videoMaxSizeBytes" to videoMaxSizeBytes,
        "streamBufferBytes" to streamBufferBytes,
        "streamMaxBitrateBps" to streamMaxBitrateBps,
        "externalStorageUuid" to externalStorageUuid,
        "externalReserveBytes" to externalReserveBytes,
        "pairPortraits" to pairPortraits,
        "freshnessWindowDays" to freshnessWindowDays,
        "minPhotoFraction" to minPhotoFraction,
        "showClock" to showClock,
        "pauseAutoResumeMillis" to pauseAutoResumeMillis,
        "showDate" to showDate,
        "selectedFolders" to selectedFolders,
    )

    /** То же самое в виде, понятном движку: он про экран ничего не знает. */
    fun cachePolicy(): CachePolicy = CachePolicy(
        budgetBytes = cacheBudgetBytes,
        itemThresholdBytes = cacheItemThresholdBytes,
        prefetchCount = prefetchCount,
    )

    companion object {
        val MIN_SHOW_DURATION_MILLIS = 5_000L
        val MAX_SHOW_DURATION_MILLIS = 60 * 60_000L
    }
}
