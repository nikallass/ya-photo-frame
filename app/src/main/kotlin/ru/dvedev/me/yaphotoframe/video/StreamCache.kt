package ru.dvedev.me.yaphotoframe.video

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import ru.dvedev.me.yaphotoframe.engine.StreamPrimer
import java.io.File

/**
 * Буфер потока на диске — один на процесс.
 *
 * Плеер читает из него при показе, подкачка кладёт в него заранее. Объём
 * ограничен: давно не тронутое вытесняется само, так что даже череда
 * гигабайтных роликов не съест место на телевизоре. Именно поэтому тяжёлые
 * ролики не кладутся в обычный кэш целиком — а без подкачки они заикались с
 * первых секунд: канал телевизора не тянет битрейт съёмки.
 *
 * Библиотека держит на директории замок, поэтому экземпляр один; при смене
 * объёма пересоздаётся — это делается на старте показа, когда никто не играет.
 */
object StreamCache {

    private const val DIRECTORY = "stream"

    private var cache: SimpleCache? = null
    private var budgetBytes = 0L

    @Synchronized
    fun open(context: Context, budget: Long): SimpleCache {
        cache?.let { if (budgetBytes == budget) return it }
        cache?.release()
        val directory = File(context.cacheDir, DIRECTORY)
        val opened = SimpleCache(
            directory,
            LeastRecentlyUsedCacheEvictor(budget.coerceAtLeast(MIN_BUDGET_BYTES)),
            StandaloneDatabaseProvider(context),
        )
        cache = opened
        budgetBytes = budget
        return opened
    }

    @Synchronized
    fun current(): SimpleCache? = cache

    /** Выбрасывает буфер целиком — при смене папки. */
    @Synchronized
    fun clear(context: Context) {
        cache?.release()
        cache = null
        budgetBytes = 0L
        File(context.cacheDir, DIRECTORY).deleteRecursively()
    }

    /** Источник данных: сначала буфер, чего нет — из сети, и это тоже ложится в буфер. */
    fun dataSourceFactory(cache: SimpleCache): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Даже при нулевой настройке буфер существует: плеер всё равно читает через него. */
    private const val MIN_BUDGET_BYTES = 16L * 1024 * 1024
}

/** Подкачка начала ролика через тот же буфер, из которого потом читает плеер. */
class ExoStreamPrimer(private val cache: SimpleCache) : StreamPrimer {

    override fun primedBytes(key: String, limit: Long): Long = cache.getCachedBytes(key, 0, limit)

    override fun usedBytes(): Long = cache.cacheSpace

    override suspend fun prime(key: String, url: String, bytes: Long, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val spec = DataSpec.Builder()
                .setUri(url)
                .setKey(key)
                .setPosition(0)
                .setLength(bytes)
                .build()
            val writer = CacheWriter(
                StreamCache.dataSourceFactory(cache).createDataSource(),
                spec,
                null,
            ) { _, bytesCached, _ -> onProgress(bytesCached) }
            // Отмена снаружи прерывает поток — писатель на это и рассчитан.
            runInterruptible { writer.cache() }
        }
}
