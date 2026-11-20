package com.aybrkaknc.songshare.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aybrkaknc.songshare.Session
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.withLanguage
import com.aybrkaknc.songshare.ytdl.Ytdl
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

// Keeps the yt-dlp download alive while the user leaves the app; progress goes to Session and the notification.
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var processId: String? = null
    // A link shared while another download runs: the old one is canceled and this one starts after it.
    private var next: Pair<String, String?>? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(withLanguage(newBase, Prefs(newBase).language))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            next = null
            processId?.let(Ytdl::cancel) ?: stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifications.ID_DOWNLOAD, Notifications.download(this, -1f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME)
        val running = processId
        if (running != null) {
            next = url to name
            Ytdl.cancel(running)
        } else {
            run(url, name)
        }
        return START_NOT_STICKY
    }

    private fun run(url: String, name: String?) {
        val id = UUID.randomUUID().toString()
        processId = id
        scope.launch {
            try {
                var shown = 0f
                val result = Ytdl.download(this@DownloadService, url, id, { Session.onConverting() }) { p ->
                    Session.onProgress(p)
                    if (p - shown >= 1f) {
                        shown = p
                        Notifications.update(this@DownloadService, p)
                    }
                }
                Session.onDownloaded(applicationContext, result, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YoutubeDL.CanceledException) {
                if (next == null) Session.onCanceled(applicationContext)
            } catch (e: Exception) {
                if (next == null) Session.onFailed(applicationContext, e.message)
            } finally {
                processId = null
                val queued = next
                next = null
                if (queued != null) {
                    run(queued.first, queued.second)
                } else {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        processId?.let(Ytdl::cancel)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.aybrkaknc.songshare.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, url: String, name: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
