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

    /**
     * 读超时实测过是必须收紧的。
     *
     * 直连后端时往返中位数 1~3 秒，长尾能挂到 8~20 秒才失败。之前 15 秒的读超时意味着
     * 一个卡死的请求会白白占住连接 15 秒；配合「同一时刻只允许一个上报在途」的策略，
     * 那 15 秒里新的播放状态一条都发不出去。
     * 收到 8 秒后，卡死的请求尽快失败，由下一次上报用最新状态顶替。
     */
    private const val READ_TIMEOUT_SECONDS = 8L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(EdgeDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
