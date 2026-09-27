package com.example.social_music.model

/** 从 NeriPlayer 邀请口令里解析出来的本地房间信息（含密钥，仅房主持有） */
data class RoomInfo(
    val rawUri: String,
    val roomId: String?,
    val inviter: String?,
    val secret: String?,
    val serverUrl: String?,
    val currentSong: String? = null,
    val currentCover: String? = null
)

/** 一位注册成员。isOnline / isHosting 是实时状态，用来在名单上打角标 */
data class MemberInfo(
    val qq: String,
    val username: String,
    val avatarUrl: String,
    val isHosting: Boolean,
    val isOnline: Boolean = false
)

/** 服务端下发的权威房间状态 */
data class ActiveRoom(
    val roomId: String,
    val inviter: String?,
    val publisher: String?,
    val publisherQq: String?,
    val hostAvatarUrl: String?,
    val deepLink: String?,
    val serverUrl: String?,
    val currentSong: String?,
    val currentCover: String?,
    val durationMs: Long,
    val basePositionMs: Long,
    val baseTimestampMs: Long,
    val playbackRate: Double,
    val isPlaying: Boolean
)

/** 一次完整的服务端快照：房间 + 在线成员 + 服务器时间 */
data class RoomSnapshot(
    val version: Long,
    val serverTime: Long,
    val room: ActiveRoom?,
    val members: List<MemberInfo>
)

/** 房主上报的播放状态 */
data class PlaybackPayload(
    val currentSong: String?,
    val currentCover: String?,
    val durationMs: Long,
    val basePositionMs: Long,
    val isPlaying: Boolean,
    val playbackRate: Double = 1.0
)
