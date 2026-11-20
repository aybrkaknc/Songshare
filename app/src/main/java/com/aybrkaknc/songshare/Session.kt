package com.aybrkaknc.songshare

import android.content.Context
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.service.DownloadService
import com.aybrkaknc.songshare.service.Notifications
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.source.Link
import com.aybrkaknc.songshare.source.Metadata
import com.aybrkaknc.songshare.source.Source
import com.aybrkaknc.songshare.ytdl.Candidate
import com.aybrkaknc.songshare.ytdl.Downloaded
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The shared link that is going through the find-download-trim-send steps.
data class Song(val id: Long, val text: String) {
    val label: String get() = Link.parse(text)?.url ?: text
}

// One song at a time. Lives as long as the process, so the work goes on while the panel is closed.
object Session {
    val step = MutableStateFlow<Step>(Step.Home)
    val active = MutableStateFlow<Song?>(null)

    // A link shared while a song is in progress; the user decides whether it replaces the current one.
    val pendingReplace = MutableStateFlow<String?>(null)
    private val visible = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var nextId = 0L
    private const val LONG_RESULT_SEC = 600
    private var attached = false

    // The song that starts as soon as the running download has stopped.
    private var replacement: Song? = null

    // Keeps the status notification in line with the active step while the panel is closed.
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        scope.launch {
            combine(step, visible) { s, shown ->
                // The download service shows its own progress notification.
                s.takeUnless { shown || active.value == null || it is Step.Downloading }
            }.distinctUntilChanged().collect { waiting ->
                Notifications.status(withLanguage(app, Prefs(app).language), waiting)
            }
        }
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun submit(context: Context, text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        when {
            active.value == null -> start(context, Song(nextId++, trimmed))
            // Searching or waiting on the trim screen is not work in progress, so the new link replaces it without asking.
            step.value is Step.Search || step.value is Step.Trim -> {
                pendingReplace.value = trimmed
                confirmReplace(context)
            }
            else -> pendingReplace.value = trimmed
        }
    }

    fun keepCurrent() {
        pendingReplace.value = null
    }

    fun confirmReplace(context: Context) {
        val text = pendingReplace.value ?: return
        pendingReplace.value = null
        val song = Song(nextId++, text)
        job?.cancel()
        if (step.value is Step.Downloading) {
            // The new song starts once the running download has stopped.
            replacement = song
            DownloadService.cancel(context)
        } else {
            clearFiles(context)
            start(context, song)
        }
    }

    // Ends the song (sent, skipped or canceled) and goes back to the start.
    fun finish(context: Context) {
        job?.cancel()
        replacement = null
        if (step.value is Step.Downloading) {
            step.value = Step.Home
            DownloadService.cancel(context)
        }
        clearFiles(context)
        active.value = null
        step.value = Step.Home
    }

    fun retry(context: Context) {
        active.value?.let { open(context, it.text) }
    }

    fun typeManually() {
        step.value = Step.Search(null, "")
    }

    private fun clearFiles(context: Context) {
        File(context.cacheDir, "dl").deleteRecursively()
        File(context.cacheDir, "share").deleteRecursively()
    }

    private fun start(context: Context, song: Song) {
        active.value = song
        open(context, song.text)
    }

    // Starts the waiting replacement, if there is one. Returns true when it did.
    private fun startReplacement(context: Context): Boolean {
        val next = replacement ?: return false
        replacement = null
        clearFiles(context)
        start(context, next)
        return true
    }

    private fun open(context: Context, text: String) {
        val link = Link.parse(text)
        when {
            link == null -> {
                val query = text.trim()
                step.value = Step.Search(null, query)
                search(context, query)
            }
            link.source == Source.UNSUPPORTED -> step.value = Step.Failed(R.string.error_unsupported, link.url)
            link.source == Source.YOUTUBE -> startDownload(context, link.url, null)
            else -> {
                job?.cancel()
                // The song is looked up on the search screen itself, which shows a spinner until the name is known.
                step.value = Step.Search(null, "", loading = true)
                job = scope.launch {
                    val prefs = Prefs(context)
                    AppLog.d("open ${link.source}")
                    val info = withContext(Dispatchers.IO) {
                        // Spotify API first when keys are set; the page read and shared text are the fallbacks.
                        val api = if (link.source == Source.SPOTIFY) {
                            Metadata.fromSpotifyApi(link, prefs.spotifyClientId, prefs.spotifyClientSecret)
                        } else {
                            null
                        }
                        api ?: Metadata.fromOpenGraph(link) ?: Metadata.fromSharedText(link)
                    }
                    AppLog.d("metadata: ${info?.query ?: "none"}")
                    if (info == null) {
                        step.value = Step.Search(null, "", error = R.string.error_metadata)
                    } else {
                        step.value = Step.Search(info, info.query)
                        search(context, info.query)
                    }
                }
            }
        }
    }

    fun search(context: Context, query: String) {
        val current = step.value as? Step.Search ?: return
        if (query.isBlank()) return
        if (Link.parse(query) != null) {
            open(context, query)
            return
        }
        step.value = current.copy(query = query, loading = true, error = null)
        job?.cancel()
        job = scope.launch {
            val result = runCatching { Ytdl.search(context, query.trim()) }
            result.exceptionOrNull()?.let { AppLog.d("search failed: ${it.message}") }
            val s = step.value as? Step.Search ?: return@launch
            val all = result.getOrDefault(emptyList())
            // Albums and mixes are left out when the setting is on; if that would leave nothing, they stay.
            val list = if (Prefs(context).hideLongResults) {
                all.filter { it.durationSec <= LONG_RESULT_SEC }.ifEmpty { all }
            } else {
                all
            }
            step.value = s.copy(
                loading = false,
                candidates = list,
                error = when {
                    result.isFailure -> R.string.error_search
                    list.isEmpty() -> R.string.error_no_results
                    else -> null
                },
            )
        }
    }

    fun pick(context: Context, candidate: Candidate) {
        val s = step.value as? Step.Search ?: return
        // The link's artist/title name the clip unless the user searched for something else.
        val info = s.info?.takeIf { it.query == s.query }
        val name = when {
            info == null -> candidate.title
            info.artist.isBlank() -> info.title
            else -> "${info.artist} - ${info.title}"
        }
        startDownload(context, candidate.url, name, candidate)
    }

    private fun startDownload(context: Context, url: String, name: String?, picked: Candidate? = null) {
        job?.cancel()
        step.value = Step.Downloading(-1f, picked)
        DownloadService.start(context, url, name)
    }

    fun cancelDownload(context: Context) = DownloadService.cancel(context)

    fun onConverting() {
        (step.value as? Step.Downloading)?.let { step.value = it.copy(converting = true) }
    }

    fun onProgress(progress: Float) {
        (step.value as? Step.Downloading)?.let { step.value = it.copy(progress = progress) }
    }

    suspend fun onDownloaded(context: Context, result: Downloaded, name: String?) {
        if (startReplacement(context)) return
        val duration = withContext(Dispatchers.IO) { Audio.durationMs(result.file) }
        // The untouched download is kept as soon as it arrives.
        val unedited = Audio.safeFileName(name ?: result.title.ifBlank { result.file.nameWithoutExtension }) + ".mp3"
        withContext(Dispatchers.IO) { Audio.saveClip(context, result.file, unedited, Audio.UNEDITED, Prefs(context).saveDir) }
        val fallback = when {
            result.title.isBlank() -> result.file.nameWithoutExtension
            result.artist.isBlank() -> result.title
            else -> "${result.artist} - ${result.title}"
        }
        if (step.value !is Step.Downloading) return
        if (duration <= 0) {
            step.value = Step.Failed(R.string.error_download, "duration")
            return
        }
        step.value = Step.Trim(result.file, name ?: fallback, duration)
    }

    fun onFailed(context: Context, detail: String?) {
        if (startReplacement(context)) return
        if (step.value !is Step.Downloading) return
        AppLog.d("download failed: $detail")
        step.value = Step.Failed(R.string.error_download, detail)
    }

    // The download was canceled from the panel or the notification, or to make room for a new song.
    fun onCanceled(context: Context) {
        if (startReplacement(context)) return
        if (step.value !is Step.Downloading) return
        step.value = Step.Home
        finish(context)
    }
}
