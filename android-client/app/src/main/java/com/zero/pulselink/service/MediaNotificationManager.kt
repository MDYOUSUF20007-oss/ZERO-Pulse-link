package com.zero.pulselink.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zero.pulselink.MainActivity
import com.zero.pulselink.ZeroPulseLinkApp
import com.zero.pulselink.R

class MediaNotificationManager(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 59100
        const val ACTION_DISCONNECT = "com.zero.pulselink.ACTION_DISCONNECT"
        const val ACTION_TOGGLE_MUTE = "com.zero.pulselink.ACTION_TOGGLE_MUTE"
    }

    fun buildNotification(
        serverName: String,
        codec: String,
        rttMs: Float,
        isMuted: Boolean
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, AudioStreamForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, AudioStreamForegroundService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (isMuted) "Unmute" else "Mute"

        return NotificationCompat.Builder(context, ZeroPulseLinkApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Zero Pulse Link • $serverName")
            .setContentText("Latency: ${String.format("%.1f", rttMs)}ms • $codec")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_lock_silent_mode, muteActionTitle, muteIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
            .build()
    }
}
