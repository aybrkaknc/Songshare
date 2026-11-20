package com.aybrkaknc.songshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.ShareActivity
import com.aybrkaknc.songshare.Step

// All notifications are on a low-importance channel, so they are silent.
object Notifications {
    const val ID_DOWNLOAD = 1
    private const val ID_STATUS = 2
    private const val CHANNEL = "download"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channel(context: Context) = manager(context).createNotificationChannel(
        NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
    )

    private fun openPanel(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, ShareActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // progress < 0 shows an indeterminate bar (before yt-dlp reports a percentage).
    fun download(context: Context, progress: Float): Notification {
        channel(context)
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
            .setContentIntent(openPanel(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.cancel), cancel).build())
            .build()
    }

    fun update(context: Context, progress: Float) = manager(context).notify(ID_DOWNLOAD, download(context, progress))

    // What the active song waits for while the panel is closed; null removes the notification.
    fun status(context: Context, step: Step?) {
        val text = when (step) {
            is Step.Search -> if (step.loading) R.string.resolving else R.string.status_pick
            is Step.Trim -> R.string.status_trim
            is Step.Failed -> R.string.status_failed
            else -> null
        }
        if (text == null) {
            manager(context).cancel(ID_STATUS)
            return
        }
        channel(context)
        val open = openPanel(context)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (step is Step.Trim) step.name else context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (step is Step.Search && step.loading) builder.setProgress(0, 0, true)
        if (step is Step.Trim) {
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.trim_and_share), open).build())
        }
        manager(context).notify(ID_STATUS, builder.build())
    }
}
