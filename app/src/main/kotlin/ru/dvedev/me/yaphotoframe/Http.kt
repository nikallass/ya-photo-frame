package ru.dvedev.me.yaphotoframe

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Общий HTTP-клиент.
 *
 * Один на всё приложение: OkHttp держит пул соединений, а на медленном телевизоре
 * переиспользование TLS-сессии заметно экономит время до появления кадра.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
