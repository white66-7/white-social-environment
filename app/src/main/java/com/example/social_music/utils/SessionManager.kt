package com.example.social_music.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_session_pref", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_AVATAR_URI = "auth_avatar_uri"
        private const val KEY_HOSTING_ROOM = "hosting_room_json"
    }

    fun saveAuthToken(token: String, username: String) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putString(KEY_USERNAME, username)
        }
    }

    fun saveUsername(username: String) {
        prefs.edit { putString(KEY_USERNAME, username) }
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "未登录") ?: "未登录"
    }

    fun saveAvatarUri(uri: String) {
        prefs.edit { putString(KEY_AVATAR_URI, uri) }
    }

    fun getAvatarUri(): String? {
        return prefs.getString(KEY_AVATAR_URI, null)
    }

    fun isLoggedIn(): Boolean {
        return !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()
    }

    // ===== 房主身份持久化 =====
    fun saveHostingRoom(json: String) {
        prefs.edit { putString(KEY_HOSTING_ROOM, json) }
    }

    fun getHostingRoom(): String? {
        return prefs.getString(KEY_HOSTING_ROOM, null)
    }

    fun clearHostingRoom() {
        prefs.edit { remove(KEY_HOSTING_ROOM) }
    }

    fun clearSession() {
        prefs.edit { clear() }
    }

    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }
}