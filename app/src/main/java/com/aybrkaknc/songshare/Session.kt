package com.aybrkaknc.songshare

import android.content.Context
import android.media.MediaMetadataRetriever
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.service.DownloadService
import com.aybrkaknc.songshare.service.Notifications
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.source.Link
import com.aybrkaknc.songshare.source.Metadata
import com.aybrkaknc.songshare.source.Source
import com.aybrkaknc.songshare.lyrics.splitArtistTitle
import com.aybrkaknc.songshare.share.ClipDraft
import com.aybrkaknc.songshare.share.ShareKind
import com.aybrkaknc.songshare.source.TrackInfo
import com.aybrkaknc.songshare.ytdl.Candidate
import com.aybrkaknc.songshare.ytdl.Downloaded
import com.aybrkaknc.songshare.ytdl.Suggest
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The shared link that is going through the find-download-trim-send steps.
data class Song(val id: Long, val text: String) {
    val label: String get() = Link.parse(text)?.url ?: text
}

// One song at a time. Lives as long as the process, so the work goes on while the panel is closed.
object Session {
    val step = MutableStateFlow<Step>(Step.Home)
    // The trim page title asks for the name window (artist, title, file name) when tapped.
    val renameRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val active = MutableStateFlow<Song?>(null)

    // A link shared while a song is in progress; the user decides whether it replaces the current one.
    val pendingReplace = MutableStateFlow<String?>(null)
    private val visible = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var nextId = 0L

    // Artist, title and cover of the song being downloaded, handed to the trim page once it arrives.
    private data class TrackMeta(val artist: String, val title: String, val thumb: String?)
    private var pendingMeta: TrackMeta? = null

    // Links already read this session, so sharing the same link again skips the network.
    private val linkCache = HashMap<String, TrackInfo>()
    private const val LONG_RESULT_SEC = 600
    private var attached = false

    // The song that starts as soon as the running download has stopped.
    private var replacement: Song? = null

    // Keeps the status notification in line with the active step while the panel is closed.
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        recents.value = loadRecents(app)
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
            step.value is Step.Search || step.value is Step.Trim || step.value is Step.Share -> {
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
        // Checking the save folder (or asking about a saved copy) is not a running download yet.
        val checking = savedCopy.value != null || job?.isActive == true
        savedCopy.value = null
        job?.cancel()
        if (step.value is Step.Downloading && !checking) {
            // The new song starts once the running download has stopped.
            replacement = song
            DownloadService.cancel(context)
        } else {
            clearFiles(context)
            start(context, song)
        }
    }

    // The trim page's edits, kept while the share page is open so Back can put them back.
    var draft: ClipDraft? = null
        private set

    fun toShare(trim: Step.Trim, edits: ClipDraft, files: Map<ShareKind, File>, initial: ShareKind) {
        draft = edits
        step.value = Step.Share(trim, edits, files, initial)
    }

    fun backToTrim() {
        val s = step.value as? Step.Share ?: return
        step.value = s.trim
    }

    // Ends the song (sent, skipped or canceled) and goes back to the start.
    fun finish(context: Context) {
        job?.cancel()
        replacement = null
        draft = null
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

    // The share folder is left alone: the app a clip was sent to may still be reading it. The next share clears it.
    private fun clearFiles(context: Context) {
        File(context.cacheDir, "dl").deleteRecursively()
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
                    val info = linkCache[link.url] ?: withContext(Dispatchers.IO) {
                        // The Spotify API and the page read run at the same time; the first good answer wins.
                        // The shared text is the last resort.
                        coroutineScope {
                            val api = async {
                                if (link.source == Source.SPOTIFY) {
                                    AppLog.timed("metadata: spotify api") {
                                        Metadata.fromSpotifyApi(link, prefs.spotifyClientId, prefs.spotifyClientSecret)
                                    }
                                } else {
                                    null
                                }
                            }
                            val page = async { AppLog.timed("metadata: page read") { Metadata.fromOpenGraph(link) } }
                            val fromApi = api.await()
                            if (fromApi != null) {
                                page.cancel()
                                fromApi
                            } else {
                                page.await() ?: Metadata.fromSharedText(link)
                            }
                        }
                    }?.also { linkCache[link.url] = it }
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

    // "Show more" asks again for a bigger page of the same query.
    fun searchMore(context: Context) {
        val current = step.value as? Step.Search ?: return
        if (current.loading || current.limit >= SEARCH_MAX) return
        search(context, current.query, current.limit + SEARCH_PAGE, more = true)
    }

    // more keeps the shown results while the bigger page loads; a new query clears them.
    fun search(context: Context, query: String, limit: Int = SEARCH_PAGE, more: Boolean = false) {
        val current = step.value as? Step.Search ?: return
        if (query.isBlank()) return
        if (Link.parse(query) != null) {
            open(context, query)
            return
        }
        step.value = current.copy(
            query = query,
            loading = true,
            error = null,
            limit = limit,
            candidates = if (more) current.candidates else emptyList(),
        )
        job?.cancel()
        Ytdl.cancelSearch()
        job = scope.launch {
            val result = runCatching { AppLog.timed("search total") { Ytdl.search(context, query.trim(), limit) } }
            // A superseded search must not write its result over the newer one.
            if (!isActive) return@launch
            result.exceptionOrNull()?.let { AppLog.d("search failed: ${it.message}") }
            val s = step.value as? Step.Search ?: return@launch
            val all = result.getOrDefault(emptyList())
            // Albums and mixes are left out when the setting is on; if that would leave nothing, they stay.
            val fresh = if (Prefs(context).hideLongResults) {
                all.filter { it.durationSec <= LONG_RESULT_SEC }.ifEmpty { all }
            } else {
                all
            }
            // "Show more" keeps the results already shown and appends only the ones it has not shown yet.
            val list = if (more) s.candidates + fresh.filter { c -> s.candidates.none { it.url == c.url } } else fresh
            // An empty result asks for a suggestion; a failed request just shows no suggestion.
            val suggestion = if (list.isEmpty()) runCatching { Suggest.first(query.trim()) }.getOrNull() else null
            if (!isActive) return@launch
            step.value = s.copy(
                loading = false,
                candidates = list,
                error = when {
                    result.isFailure -> R.string.error_search
                    list.isEmpty() -> R.string.error_no_results
                    else -> null
                },
                suggestion = suggestion?.takeIf { !it.equals(query.trim(), ignoreCase = true) },
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
        val (artist, title) = if (info != null) info.artist to info.title else splitArtistTitle(candidate.title)
        startDownload(context, candidate.url, name, candidate, TrackMeta(artist, title, candidate.thumb))
    }

    // A copy of the song already in the save folder; the panel asks whether to use it or download again.
    data class SavedCopy(val file: File, val url: String, val name: String?)
    val savedCopy = MutableStateFlow<SavedCopy?>(null)

    private val youtubeId = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""")

    // useSavedDirectly skips the question and takes the saved copy when there is one (a recent song).
    private fun startDownload(
        context: Context,
        url: String,
        name: String?,
        picked: Candidate? = null,
        meta: TrackMeta? = null,
        useSavedDirectly: Boolean = false,
    ) {
        job?.cancel()
        pendingMeta = meta
        pendingUrl = url
        step.value = Step.Downloading(-1f, picked)
        val id = youtubeId.find(url)?.groupValues?.get(1)
        if (id == null) {
            DownloadService.start(context, url, name)
            return
        }
        // The save folder is checked first; the download only starts when the song is not there.
        job = scope.launch {
            val saved = Audio.findSavedById(context, id, Prefs(context).saveDir)
            if (step.value !is Step.Downloading) return@launch
            when {
                saved == null -> DownloadService.start(context, url, name)
                useSavedDirectly -> {
                    savedCopy.value = SavedCopy(saved, url, name)
                    useSaved(context)
                }
                else -> savedCopy.value = SavedCopy(saved, url, name)
            }
        }
    }

    // The last songs that reached the trim page, newest first; the start page lists them.
    data class Recent(val url: String, val name: String, val artist: String, val title: String, val thumb: String?)
    val recents = MutableStateFlow<List<Recent>>(emptyList())
    private const val MAX_RECENTS = 5
    private var pendingUrl: String? = null

    // A recent song: a saved copy goes straight to the trim page; without one the song is downloaded again.
    fun openRecent(context: Context, recent: Recent) {
        if (active.value != null) return
        active.value = Song(nextId++, recent.url)
        startDownload(context, recent.url, recent.name, meta = TrackMeta(recent.artist, recent.title, recent.thumb), useSavedDirectly = true)
    }

    // Only takes the song off the list; the file stays where it is.
    fun removeRecent(context: Context, recent: Recent) {
        recents.value = recents.value - recent
        saveRecents(context)
    }

    private fun addRecent(context: Context, recent: Recent) {
        recents.value = (listOf(recent) + recents.value.filter { it.url != recent.url }).take(MAX_RECENTS)
        saveRecents(context)
    }

    private fun saveRecents(context: Context) {
        val arr = JSONArray()
        recents.value.forEach { r ->
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("artist", r.artist)
                    .put("title", r.title)
                    .put("thumb", r.thumb ?: JSONObject.NULL),
            )
        }
        Prefs(context).recentsJson = arr.toString()
    }

    private fun loadRecents(context: Context): List<Recent> = runCatching {
        val arr = JSONArray(Prefs(context).recentsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recent(
                o.getString("url"),
                o.optString("name"),
                o.optString("artist"),
                o.optString("title"),
                if (o.isNull("thumb")) null else o.optString("thumb"),
            )
        }
    }.getOrDefault(emptyList())

    fun useSaved(context: Context) {
        val copy = savedCopy.value ?: return
        savedCopy.value = null
        scope.launch {
            val (artist, title) = withContext(Dispatchers.IO) {
                val tags = MediaMetadataRetriever()
                try {
                    tags.setDataSource(copy.file.absolutePath)
                    tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty() to
                        tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                } finally {
                    tags.release()
                }
            }
            onDownloaded(context, Downloaded(copy.file, artist, title), copy.name, alreadySaved = true)
        }
    }

    fun redownload(context: Context) {
        val copy = savedCopy.value ?: return
        savedCopy.value = null
        copy.file.delete()
        DownloadService.start(context, copy.url, copy.name)
    }

    // While the save folder is being checked or the question is open, no download is running yet.
    fun cancelDownload(context: Context) {
        if (savedCopy.value != null || job?.isActive == true) {
            savedCopy.value = null
            finish(context)
        } else {
            DownloadService.cancel(context)
        }
    }

    fun onConverting() {
        (step.value as? Step.Downloading)?.let { step.value = it.copy(converting = true) }
    }

    fun onProgress(progress: Float) {
        (step.value as? Step.Downloading)?.let { step.value = it.copy(progress = progress) }
    }

    // alreadySaved is true when the file came from the save folder, so it is not saved there a second time.
    suspend fun onDownloaded(context: Context, result: Downloaded, name: String?, alreadySaved: Boolean = false) {
        if (startReplacement(context)) return
        val duration = withContext(Dispatchers.IO) { Audio.durationMs(result.file) }
        // The untouched download is kept in the background, so the trim page does not wait for the copy.
        if (!alreadySaved) {
            val unedited = Audio.safeFileName(name ?: result.title.ifBlank { result.file.nameWithoutExtension }) + "." + result.file.extension
            val saveDir = Prefs(context).saveDir
            scope.launch(Dispatchers.IO) { Audio.saveClip(context, result.file, unedited, Audio.UNEDITED, saveDir) }
        }
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
        // A picked result knows its artist and title; a direct link falls back to the file's tags.
        val meta = pendingMeta ?: splitArtistTitle(result.title).let { (a, t) ->
            TrackMeta(a.ifBlank { result.artist }, t, null)
        }
        pendingMeta = null
        step.value = Step.Trim(result.file, name ?: fallback, duration, meta.artist, meta.title, meta.thumb)
        pendingUrl?.let { addRecent(context, Recent(it, name ?: fallback, meta.artist, meta.title, meta.thumb)) }
        pendingUrl = null
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
