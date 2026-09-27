package com.example.social_music.net

/** 后端地址的唯一出处，避免各处硬编码字符串各自漂移 */
object ApiConfig {

    const val BASE_URL = "https://white667.xyz/api"
    const val DEFAULT_NERI_SERVER = "https://neriplayer.hancat.work"

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
