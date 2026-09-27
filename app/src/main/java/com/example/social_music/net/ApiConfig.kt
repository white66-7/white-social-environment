package com.example.social_music.net

/** 后端地址的唯一出处，避免各处硬编码字符串各自漂移 */
object ApiConfig {

    const val BASE_URL = "https://white667.xyz/api"
    const val DEFAULT_NERI_SERVER = "https://neriplayer.hancat.work"

    /** BASE_URL 的主机名，EdgeDns 用它来限定「只对自家后端做地址优选」 */
    val BASE_HOST: String = BASE_URL.removePrefix("https://").removePrefix("http://").substringBefore("/")

    // ==========================================================
    // 连接目标调优（默认全部关闭，行为与改动前完全一致）
    //
    // 背景：后端和播放器都在 Cloudflare 上，从国内直连的实测往返是
    // 0.86s ~ 8.13s，还出现过 20 秒挂死。同一域名换一个 Cloudflare 边缘 IP
    // 实测能差到「0.6 秒」和「15 秒超时」的区别。所以「连到哪个 IP」是有价值的杠杆。
    //
    // 但杠杆是双向的：OkHttp 是**按顺序逐个尝试** DNS 返回的地址，第一个连不上
    // 就要白等一个 connectTimeout（15 秒）才轮到下一个。它虽然会把失败的地址
    // 记进 RouteDatabase 并在本次运行内推迟，但那一次 15 秒仍然会砸在用户脸上。
    // 所以下面两项默认关闭 —— 想用必须先在自己手机上量过，确认有收益再打开。
    // ==========================================================

    /**
     * 优选 IP：给指定域名手工指定 Cloudflare 边缘地址，排在系统解析结果之前。
     *
     * 怎么拿到这些地址：在**手机上**（不是电脑）跑 CloudflareSpeedTest 之类的工具，
     * 扫出对你当前网络最快、且不丢包的几个 Cloudflare IP，填进来。
     *
     * 示例：
     *   mapOf("white667.xyz" to listOf("104.21.27.145", "172.67.142.238"))
     *
     * 留空 = 不做优选，完全走系统 DNS。
     */
    val PREFERRED_EDGE_IPS: Map<String, List<String>> = emptyMap()

    /**
     * 是否把 IPv6 地址排到 IPv4 前面。
     *
     * 国内手机网络到 Cloudflare 的 IPv6 路由经常明显好于 IPv4，值得一试；
     * 但如果你的网络 IPv6 不通，第一个地址会白等一个 connectTimeout。
     * 先确认手机确实有可用的 IPv6（能打开 test-ipv6.com）再打开。
     */
    const val PREFER_IPV6 = false

    /**
     * 成员端实时长连接地址。
     * token 走查询参数而不是请求头 —— WebSocket 握手不方便加自定义头，
     * 服务端两种方式都接受。
     */
    fun realtimeUrl(token: String): String {
        val wsBase = BASE_URL
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$wsBase/room/ws?token=$token"
    }
}
