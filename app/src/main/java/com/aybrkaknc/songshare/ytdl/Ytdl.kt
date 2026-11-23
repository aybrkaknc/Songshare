package com.aybrkaknc.songshare.ytdl

import android.content.Context
import android.media.MediaMetadataRetriever
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.AppLog
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class Candidate(val url: String, val title: String, val channel: String, val durationSec: Int, val thumb: String)

data class Downloaded(val file: File, val artist: String, val title: String)

object Ytdl {
    private const val SEARCH_PROCESS = "search"
    private val initLock = Mutex()
    // Held while the yt-dlp binary is being replaced so no request runs against a half-written file.
    private val updateLock = Mutex()
    @Volatile private var ready = false

    // First run unpacks python and ffmpeg into noBackupFilesDir, which takes a few seconds.
    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (!ready) {
                AppLog.timed("yt-dlp init") {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                }
                ready = true
            }
        }
    }

    // True when a newer yt-dlp was installed, false when it was already current.
    suspend fun update(context: Context): Boolean = withContext(Dispatchers.IO) {
        ensureInit(context)
        updateLock.withLock {
            AppLog.timed("yt-dlp update") {
                YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) == YoutubeDL.UpdateStatus.DONE
            }
        }
    }

    private suspend fun ready(context: Context) {
        ensureInit(context)
        updateLock.withLock { }
    }

    fun version(context: Context): String? = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    // Stops a running search, so a superseded search does not keep using the device.
    fun cancelSearch() {
        val stopped = runCatching { YoutubeDL.getInstance().destroyProcessById(SEARCH_PROCESS) }.getOrDefault(false)
        if (stopped) AppLog.d("search: previous yt-dlp process stopped")
    }

    suspend fun search(context: Context, query: String, limit: Int): List<Candidate> = withContext(Dispatchers.IO) {
        AppLog.timed("search: wait for yt-dlp") { ready(context) }
        val request = YoutubeDLRequest("ytsearch$limit:$query")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--ignore-config")
            .addOption("--no-cache-dir")
            .addOption("--no-warnings")
            .addOption("--force-ipv4")
            .addOption("--no-check-certificates")
            .addOption("--extractor-args", "youtube:player_skip=webpage,configs,js")
        val response = AppLog.timed("search: yt-dlp run") { YoutubeDL.getInstance().execute(request, SEARCH_PROCESS) }
        val entries = JSONObject(response.out).optJSONArray("entries") ?: return@withContext emptyList()
        (0 until entries.length()).mapNotNull { i ->
            val e = entries.getJSONObject(i)
            val id = e.optString("id").ifBlank { return@mapNotNull null }
            Candidate(
                url = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" },
                title = e.optString("title"),
                channel = e.optString("channel").ifBlank { e.optString("uploader") },
                durationSec = e.optDouble("duration", 0.0).toInt(),
                thumb = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            )
        }
    }

    // Formats kept as they come from YouTube; anything else is converted to MP3 once.
    private val KEEP_FORMATS = setOf("m4a", "mp3", "aac", "ogg")

    // Downloads the audio stream without re-encoding it. --embed-metadata writes artist/title tags, which become the default file name.
    suspend fun download(
        context: Context,
        url: String,
        processId: String,
        onConverting: () -> Unit,
        onProgress: (Float) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        ready(context)
        val dir = File(context.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            .addOption("--embed-metadata")
            // The video id goes into the comment tag, so a saved copy can be recognised later without downloading again.
            .addOption("--parse-metadata", "%(id)s:%(meta_comment)s")
            .addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }
        val downloaded = dir.listFiles()
            ?.firstOrNull { it.isFile && it.extension !in setOf("part", "json", "ytdl") }
            ?: throw IOException("no audio output")
        val file = if (downloaded.extension in KEEP_FORMATS) {
            downloaded
        } else {
            onConverting()
            File(dir, downloaded.nameWithoutExtension + ".mp3").also { Audio.toMp3(context, downloaded, it) }
        }
        val tags = MediaMetadataRetriever()
        try {
            tags.setDataSource(file.absolutePath)
            Downloaded(
                file,
                tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
            )
        } finally {
            tags.release()
        }
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }
}
