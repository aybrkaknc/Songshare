package com.aybrkaknc.songshare

import android.content.Context
import androidx.annotation.StringRes
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.service.DownloadService
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.settings.SpotifyMethod
import com.aybrkaknc.songshare.source.Link
import com.aybrkaknc.songshare.source.Metadata
import com.aybrkaknc.songshare.source.Source
import com.aybrkaknc.songshare.source.TrackInfo
import com.aybrkaknc.songshare.ytdl.Candidate
import com.aybrkaknc.songshare.ytdl.Downloaded
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Step {
    data object Home : Step
    data object Resolving : Step

    // info is what the source link gave; query starts from it and the user may edit it.
    data class Search(
        val info: TrackInfo?,
        val query: String,
        val candidates: List<Candidate> = emptyList(),
        val loading: Boolean = false,
        @param:StringRes val error: Int? = null,
    ) : Step

    data class Downloading(val progress: Float) : Step
    data class Trim(val file: File, val name: String, val durationMs: Long) : Step
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : Step
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Running : UpdateState
    data class Done(val updated: Boolean) : UpdateState
    data class Failed(val detail: String?) : UpdateState
}

// The current link-to-clip job. Lives as long as the process, so a running download survives rotation.
object Session {
    val step = MutableStateFlow<Step>(Step.Home)
    val update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var autoUpdated = false

    fun open(context: Context, text: String?) {
        val link = Link.parse(text)
        when {
            link == null -> step.value = Step.Failed(R.string.error_no_link)
            link.source == Source.UNSUPPORTED -> step.value = Step.Failed(R.string.error_unsupported, link.url)
            link.source == Source.YOUTUBE -> startDownload(context, link.url, null)
            else -> {
                job?.cancel()
                step.value = Step.Resolving
                job = scope.launch {
                    val prefs = Prefs(context)
                    // The method setting only concerns Spotify; the other services have no public API to choose.
                    val method = if (link.source == Source.SPOTIFY) prefs.spotifyMethod else SpotifyMethod.OPEN_GRAPH
                    if (method == SpotifyMethod.MANUAL) {
                        step.value = Step.Search(null, "")
                        return@launch
                    }
                    val info = withContext(Dispatchers.IO) {
                        when (method) {
                            SpotifyMethod.WEB_API -> Metadata.fromSpotifyApi(link, prefs.spotifyClientId, prefs.spotifyClientSecret)
                            else -> Metadata.fromOpenGraph(link)
                        } ?: Metadata.fromSharedText(link)
                    }
                    if (info == null) {
                        val error = if (method == SpotifyMethod.WEB_API && prefs.spotifyClientId.isBlank()) {
                            R.string.error_no_api_key
                        } else {
                            R.string.error_metadata
                        }
                        step.value = Step.Search(null, "", error = error)
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
        step.value = current.copy(query = query, loading = true, error = null)
        job?.cancel()
        job = scope.launch {
            val result = runCatching { Ytdl.search(context, query.trim()) }
            val s = step.value as? Step.Search ?: return@launch
            val list = result.getOrDefault(emptyList())
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
        startDownload(context, candidate.url, name)
    }

    private fun startDownload(context: Context, url: String, name: String?) {
        job?.cancel()
        step.value = Step.Downloading(-1f)
        DownloadService.start(context, url, name)
    }

    fun cancelDownload(context: Context) = DownloadService.cancel(context)

    fun onProgress(progress: Float) {
        if (step.value is Step.Downloading) step.value = Step.Downloading(progress)
    }

    suspend fun onDownloaded(context: Context, result: Downloaded, name: String?) {
        val duration = withContext(Dispatchers.IO) { Audio.durationMs(result.file) }
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

    fun onFailed(detail: String?) {
        step.value = Step.Failed(R.string.error_download, detail)
    }

    fun onCanceled() {
        step.value = Step.Home
    }

    // Back to the start screen; downloaded and trimmed files are no longer needed.
    fun reset(context: Context) {
        job?.cancel()
        if (step.value is Step.Downloading) DownloadService.cancel(context)
        step.value = Step.Home
        File(context.cacheDir, "dl").deleteRecursively()
        File(context.cacheDir, "share").deleteRecursively()
    }

    fun autoUpdate(context: Context) {
        if (autoUpdated || !Prefs(context).autoUpdate) return
        autoUpdated = true
        updateNow(context)
    }

    fun updateNow(context: Context) {
        if (update.value == UpdateState.Running) return
        update.value = UpdateState.Running
        scope.launch {
            update.value = runCatching { Ytdl.update(context.applicationContext) }
                .fold({ UpdateState.Done(it) }, { UpdateState.Failed(it.message) })
        }
    }
}
