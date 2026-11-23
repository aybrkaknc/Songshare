package com.aybrkaknc.songshare.lyrics

import android.content.Context
import com.aybrkaknc.songshare.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

// timeMs is null for lyrics without timing (plain text or typed in by the user).
data class LyricLine(val timeMs: Long?, val text: String)

// One LRCLIB record. A song can have several, from different uploads or albums.
data class LyricsVersion(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
) {
    val isSynced: Boolean get() = !synced.isNullOrBlank()
    val lines: List<LyricLine>
        get() = synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() } ?: plain?.let(::plainLines).orEmpty()
}

// Everything kept for one song: the versions found, the one the user picked, a timing correction and typed-in lyrics.
data class SongLyrics(
    val key: String,
    val versions: List<LyricsVersion>,
    val chosenId: Long?,
    val offsetMs: Long,
    val manual: String?,
) {
    val chosen: LyricsVersion? get() = versions.firstOrNull { it.id == chosenId } ?: versions.firstOrNull()
    val lines: List<LyricLine> get() = manual?.let(::plainLines) ?: chosen?.lines.orEmpty()
}

// Synced lyrics from LRCLIB (free, no key). Results are cached on the device, so a song is only looked up once.
object LyricsRepository {
    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val TIMEOUT_MS = 6_000
    private const val USER_AGENT = "LyricsClient/1.0"

    fun keyFor(artist: String, title: String): String {
        val text = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // Cached lyrics, or a fresh lookup. Null means the lookup itself failed (no network); an empty list means nothing was found.
    suspend fun load(context: Context, artist: String, title: String, durationMs: Long): SongLyrics? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = keyFor(artist, title)
        read(context, key)?.let { return@withContext it }
        val versions = search(artist, title, durationMs) ?: return@withContext null
        SongLyrics(key, versions, null, 0L, null).also { save(context, it) }
    }

    fun save(context: Context, song: SongLyrics) {
        runCatching {
            val versions = JSONArray()
            song.versions.forEach { v ->
                versions.put(
                    JSONObject()
                        .put("id", v.id)
                        .put("artist", v.artist)
                        .put("title", v.title)
                        .put("album", v.album)
                        .put("duration", v.durationSec)
                        .put("synced", v.synced ?: JSONObject.NULL)
                        .put("plain", v.plain ?: JSONObject.NULL),
                )
            }
            val json = JSONObject()
                .put("versions", versions)
                .put("chosenId", song.chosenId ?: JSONObject.NULL)
                .put("offsetMs", song.offsetMs)
                .put("manual", song.manual ?: JSONObject.NULL)
            file(context, song.key).apply { parentFile?.mkdirs() }.writeText(json.toString())
        }.onFailure { AppLog.d("lyrics save failed: ${it.message}") }
    }

    private fun read(context: Context, key: String): SongLyrics? = runCatching {
        val f = file(context, key)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val arr = json.getJSONArray("versions")
        val versions = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LyricsVersion(
                id = o.getLong("id"),
                artist = o.optString("artist"),
                title = o.optString("title"),
                album = o.optString("album"),
                durationSec = o.optDouble("duration", 0.0),
                synced = o.stringOrNull("synced"),
                plain = o.stringOrNull("plain"),
            )
        }
        SongLyrics(
            key = key,
            versions = versions,
            chosenId = if (json.isNull("chosenId")) null else json.getLong("chosenId"),
            offsetMs = json.optLong("offsetMs", 0L),
            manual = json.stringOrNull("manual"),
        )
    }.onFailure { AppLog.d("lyrics cache unreadable: ${it.message}") }.getOrNull()

    private fun file(context: Context, key: String) = File(context.filesDir, "lyrics/$key.json")

    // Field search first; if it finds nothing, a free-text search on "artist title".
    private fun search(artist: String, title: String, durationMs: Long): List<LyricsVersion>? {
        val byFields = buildString {
            append("?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
        }
        val first = request(byFields) ?: return null
        val found = first.ifEmpty { request("?q=" + enc("$artist $title".trim())) ?: return null }
        return found
            .filter { it.synced != null || it.plain != null }
            .sortedWith(compareBy({ if (it.isSynced) 0 else 1 }, { abs(it.durationSec * 1000 - durationMs) }))
    }

    private fun request(query: String): List<LyricsVersion>? = runCatching {
        val conn = URL(SEARCH_URL + query).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LyricsVersion(
                id = o.getLong("id"),
                artist = o.optString("artistName"),
                title = o.optString("trackName"),
                album = o.optString("albumName"),
                durationSec = o.optDouble("duration", 0.0),
                synced = o.stringOrNull("syncedLyrics"),
                plain = o.stringOrNull("plainLyrics"),
            )
        }
    }.onFailure { AppLog.d("lyrics search failed: ${it.message}") }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}

// Leading "[mm:ss.xx]" tags; a line can carry several when it repeats.
private val TIME_TAG = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

fun parseLrc(raw: String): List<LyricLine> = raw.lineSequence().flatMap { line ->
    var rest = line.trim()
    val times = mutableListOf<Long>()
    while (true) {
        val m = TIME_TAG.find(rest) ?: break
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        times += m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms
        rest = rest.substring(m.range.last + 1)
    }
    val text = rest.trim()
    if (times.isEmpty() || text.isBlank()) emptySequence() else times.asSequence().map { LyricLine(it, text) }
}.sortedBy { it.timeMs }.toList()

fun plainLines(raw: String): List<LyricLine> =
    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.map { LyricLine(null, it) }

// "Artist - Title (Official Video)" -> ("Artist", "Title"); without a dash the whole text is the title.
fun splitArtistTitle(text: String): Pair<String, String> {
    val cleaned = text
        .replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|klip|music|visualizer|hd|4k|mv)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .trim()
    val i = cleaned.indexOf(" - ")
    return if (i > 0) cleaned.substring(0, i).trim() to cleaned.substring(i + 3).trim() else "" to cleaned
}
