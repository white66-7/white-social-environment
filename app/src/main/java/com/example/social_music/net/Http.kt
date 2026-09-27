package com.example.social_music.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全应用共享的 HTTP 客户端。
 *
 * 之前 MainActivity / LoginActivity / SettingsActivity 各建一个 OkHttpClient，
 * 每个都自带连接池和线程池，纯属浪费；这里收敛成一个。
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
