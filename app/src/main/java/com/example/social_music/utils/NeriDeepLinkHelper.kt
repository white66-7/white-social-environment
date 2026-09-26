package com.example.social_music.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.example.social_music.model.RoomInfo
import java.util.regex.Pattern

object NeriDeepLinkHelper {
    private const val TAG = "DeepLinkHelper"
    private val SCHEME_REGEX = Pattern.compile("neriplayer://[\\w\\-./?%&=:#@+~]+")

    fun parseInvitation(text: String): RoomInfo? {
        val matcher = SCHEME_REGEX.matcher(text)
        if (!matcher.find()) return null

        val uriString = matcher.group() ?: return null
        return try {
            val uri = uriString.toUri()
            RoomInfo(
                rawUri = uriString,
                roomId = uri.getQueryParameter("roomId"),
                inviter = uri.getQueryParameter("inviter"),
                secret = uri.getQueryParameter("secret"),
                serverUrl = uri.getQueryParameter("server") ?: uri.getQueryParameter("baseUrl")
            )
        } catch (e: Exception) {
            Log.e(TAG, "URI 解析异常", e)
            null
        }
    }

    fun launchPlayer(context: Context, deepLink: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, deepLink.toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "未安装NeriPlayer", e)
            false
        }
    }
}