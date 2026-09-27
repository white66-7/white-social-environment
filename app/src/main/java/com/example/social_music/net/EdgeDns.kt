package com.example.social_music.net

import android.util.Log
import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 连接目标选择 —— 决定 OkHttp 去连 Cloudflare 的哪个边缘地址。
 *
 * 为什么需要这个：后端和播放器都在 Cloudflare 上，实测同一个域名换一个边缘 IP，
 * 从国内直连的耗时能差到「0.6 秒」和「15 秒超时」的程度。而 OkHttp 是
 * **按顺序逐个尝试** DNS 返回的地址（见 RouteSelector.resetNextInetSocketAddress ——
 * 它不做 IPv4/IPv6 交错，也不并发赛跑），所以「先试哪个」完全取决于这里返回的顺序。
 *
 * 默认行为刻意与改动前**完全一致**（系统 DNS 原样返回）。两个杠杆
 * —— 优选 IP 和 IPv6 优先 —— 都在 [ApiConfig] 里，默认关闭。
 * 原因是它们都有反作用：排在前面的地址若连不上，要先白等一个 connectTimeout
 * 才轮到下一个，而这必须在你自己的网络和手机上量过才能判断是赚是亏。
 */
object EdgeDns : Dns {

    private const val TAG = "EdgeDns"

    private val system: Dns = Dns.SYSTEM

    /** 只对同一个域名打一次日志，避免每次建连都刷屏 */
    private val logged = HashSet<String>()

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = system.lookup(hostname)
        if (resolved.isEmpty()) return resolved

        val pinned = resolvePinned(hostname)
        val reordered = if (ApiConfig.PREFER_IPV6) preferIpv6(resolved) else resolved

        val result = ArrayList<InetAddress>(pinned.size + reordered.size)
        result += pinned
        // 优选地址失败时还要能回落到系统解析结果，所以两者都要保留
        result += reordered.filter { it !in pinned }

        // 无条件记录一次。重点是看清楚「系统原本给的顺序」——
        // Android 的 getaddrinfo 按 RFC 6724 排序，在有可用 IPv6 的网络里
        // 往往已经把 AAAA 排在前面了。真是那样的话，PREFER_IPV6 就是个空操作，
        // 而更重要的结论是：慢的原因不在地址族选择上，打开它也没用。
        logOnce(hostname, resolved, result)
        return result
    }

    private fun logOnce(
        hostname: String,
        systemOrder: List<InetAddress>,
        finalOrder: List<InetAddress>
    ) {
        synchronized(logged) {
            if (!logged.add(hostname)) return
        }
        val systemV6First = systemOrder.firstOrNull() is Inet6Address
        Log.i(
            TAG,
            "$hostname 系统顺序=[${systemOrder.joinToString { it.hostAddress ?: "?" }}] " +
                "实际使用=[${finalOrder.joinToString { it.hostAddress ?: "?" }}] " +
                "系统IPv6优先=$systemV6First 优选IP=${ApiConfig.PREFERRED_EDGE_IPS[hostname]?.size ?: 0}个 " +
                "PREFER_IPV6=${ApiConfig.PREFER_IPV6}"
        )
    }

    /** 手工优选的边缘 IP（按域名配置），解析失败的直接跳过 */
    private fun resolvePinned(hostname: String): List<InetAddress> {
        val ips = ApiConfig.PREFERRED_EDGE_IPS[hostname] ?: return emptyList()
        return ips.mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip) }
                .onFailure { Log.w(TAG, "优选 IP 无法解析，已跳过: $ip") }
                .getOrNull()
        }
    }

    /** IPv6 提前；同族之间保持系统给的相对顺序（sortedBy 是稳定排序） */
    private fun preferIpv6(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        if (addresses.none { it is Inet6Address }) return addresses
        return addresses.sortedByDescending { it is Inet6Address }
    }
}
