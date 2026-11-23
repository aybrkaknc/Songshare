package com.aybrkaknc.songshare.cover

import com.aybrkaknc.songshare.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Album art from MusicBrainz (finds the release) and the Cover Art Archive (serves the image). Both are free and need no key.
object CoverArt {
    private const val USER_AGENT = "Songshare/1.0"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_RELEASES = 5
    // MusicBrainz asks for at most one request per second.
    private const val MIN_GAP_MS = 1_100L

    private val cache = HashMap<String, String>()
    private var lastMusicBrainz = 0L

    // URL of a front cover for the song, or null when none is found.
    suspend fun find(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val found = runCatching { lookup(artist, title) }
            .onFailure { AppLog.d("cover lookup failed: ${it.message}") }
            .getOrNull()
        found?.let { synchronized(cache) { cache[key] = it } }
        found
    }

    private fun lookup(artist: String, title: String): String? {
        val query = buildString {
            append("recording:\"").append(title.replace("\"", "")).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(artist.replace("\"", "")).append('"')
        }
        val body = musicBrainz("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=5&query=" + URLEncoder.encode(query, "UTF-8"))
            ?: return null
        val recordings = JSONObject(body).optJSONArray("recordings") ?: return null
        val ids = mutableListOf<String>()
        for (i in 0 until recordings.length()) {
            val releases = recordings.getJSONObject(i).optJSONArray("releases") ?: continue
            for (j in 0 until releases.length()) ids += releases.getJSONObject(j).getString("id")
        }
        return ids.distinct().take(MAX_RELEASES).firstNotNullOfOrNull { id ->
            "https://coverartarchive.org/release/$id/front-500".takeIf { exists(it) }
        }
    }

    // Waits out the MusicBrainz rate limit, then reads the JSON answer.
    private fun musicBrainz(url: String): String? {
        synchronized(this) {
            val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastMusicBrainz)
            if (wait > 0) Thread.sleep(wait)
            lastMusicBrainz = System.currentTimeMillis()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }

    // The archive answers with a redirect to the image; a 200 after redirects means the cover exists.
    private fun exists(url: String): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }
}
