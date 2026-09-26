package com.example.social_music.model

data class RoomInfo(
    val rawUri: String,
    val roomId: String?,
    val inviter: String?,
    val secret: String?,
    val serverUrl: String?,
    val currentSong: String? = null,
    val currentCover: String? = null
)

data class RegisteredMember(
    val username: String,
    val avatarUrl: String,
    val isHosting: Boolean
)