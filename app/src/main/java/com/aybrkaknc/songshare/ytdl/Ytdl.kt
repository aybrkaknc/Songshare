package com.aybrkaknc.songshare.ytdl

import android.content.Context
import android.media.MediaMetadataRetriever
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

data class Candidate(val url: String, val title: String, val channel: String, val durationSec: Int)

data class Downloaded(val file: File, val artist: String, val title: String)

object Ytdl {
    private val initLock = Mutex()
    // Held while the yt-dlp binary is being replaced so no request runs against a half-written file.
    private val updateLock = Mutex()
    @Volatile private var ready = false

    // First run unpacks python and ffmpeg into noBackupFilesDir, which takes a few seconds.
    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
            }
        }
    }

    // True when a newer yt-dlp was installed, false when it was already current.
    suspend fun update(context: Context): Boolean = withContext(Dispatchers.IO) {
        ensureInit(context)
        updateLock.withLock {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) == YoutubeDL.UpdateStatus.DONE
        }
    }

    private suspend fun ready(context: Context) {
        ensureInit(context)
        updateLock.withLock { }
    }

    fun version(context: Context): String? = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    suspend fun search(context: Context, query: String): List<Candidate> = withContext(Dispatchers.IO) {
        ready(context)
        val request = YoutubeDLRequest("ytsearch5:$query")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
        val response = YoutubeDL.getInstance().execute(request)
        val entries = JSONObject(response.out).optJSONArray("entries") ?: return@withContext emptyList()
        (0 until entries.length()).mapNotNull { i ->
            val e = entries.getJSONObject(i)
            val id = e.optString("id").ifBlank { return@mapNotNull null }
            Candidate(
                url = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" },
                title = e.optString("title"),
                channel = e.optString("channel").ifBlank { e.optString("uploader") },
                durationSec = e.optDouble("duration", 0.0).toInt(),
            )
        }
    }

    // Downloads the audio as 192 kbps MP3. --embed-metadata writes artist/title tags, which become the default file name.
    suspend fun download(
        context: Context,
        url: String,
        processId: String,
        onProgress: (Float) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        ready(context)
        val dir = File(context.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("-x")
            .addOption("--audio-format", "mp3")
            .addOption("--audio-quality", "192K")
            .addOption("--embed-metadata")
            .addOption("-o", "${dir.absolutePath}/%(id)s.%(ext)s")
        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }
        val file = dir.listFiles()?.firstOrNull { it.extension == "mp3" } ?: throw IOException("no mp3 output")
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
