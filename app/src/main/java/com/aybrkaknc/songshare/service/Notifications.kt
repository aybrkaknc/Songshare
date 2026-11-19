package com.aybrkaknc.songshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aybrkaknc.songshare.MainActivity
import com.aybrkaknc.songshare.R

object Notifications {
    const val ID_DOWNLOAD = 1
    private const val CHANNEL = "download"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    // progress < 0 shows an indeterminate bar (before yt-dlp reports a percentage).
    fun download(context: Context, progress: Float): Notification {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            context, 1,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (progress < 0) context.getString(R.string.downloading)
        else context.getString(R.string.downloading_percent, progress.toInt())
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress.toInt().coerceIn(0, 100), progress < 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.cancel), cancel).build())
            .build()
    }

    fun update(context: Context, progress: Float) = manager(context).notify(ID_DOWNLOAD, download(context, progress))
}
