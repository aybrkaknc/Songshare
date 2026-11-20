package com.aybrkaknc.songshare.source

import android.text.Html
import android.util.Base64
import com.aybrkaknc.songshare.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TrackInfo(val artist: String, val title: String) {
    val query: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
}

// Reads artist and title from a music service link. Page formats change over time, so every
// parser is best effort: a null result sends the user to the editable search box instead.
object Metadata {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val MAX_HTML = 1_500_000

    // Spotify's track page has no title tags in the served HTML, but its embed page carries the track JSON.
    private fun fromSpotifyEmbed(link: SharedLink): TrackInfo? {
        val id = spotifyTrackId(resolveRedirects(link.url))
        if (id == null) {
            AppLog.d("spotify: no track id in ${link.url}")
            return null
        }
        val html = fetch("https://open.spotify.com/embed/track/$id") ?: return null
        val match = Regex("\"title\":\"([^\"]+)\",\"artists\":\\[\\{\"name\":\"([^\"]+)\"").find(html)
        if (match == null) {
            AppLog.d("spotify: no track data on embed page")
            return null
        }
        return TrackInfo(unescape(match.groupValues[2]), unescape(match.groupValues[1])).takeIf { it.title.isNotBlank() }
    }

    fun fromOpenGraph(link: SharedLink): TrackInfo? {
        if (link.source == Source.SPOTIFY) return fromSpotifyEmbed(link)
        val html = fetch(link.url) ?: return null
        val tags = metaTags(html)
        val ogTitle = tags["og:title"].orEmpty()
        val info = when (link.source) {
            Source.APPLE_MUSIC -> splitBy(
                ogTitle.replace("‎", "")
                    .replace(Regex("""\s*(on|[-–])\s*Apple Music\s*$"""), "")
                    .trim(),
            )
            Source.SOUNDCLOUD -> {
                val page = Regex("""<title>\s*Stream (.+) by (.+?) \|""").find(html)
                if (page != null) TrackInfo(decode(page.groupValues[2]), decode(page.groupValues[1]))
                else splitBy(ogTitle)
            }
            Source.BANDCAMP -> {
                val parts = ogTitle.split(", by ", limit = 2)
                if (parts.size == 2) TrackInfo(parts[1], parts[0]) else TrackInfo("", ogTitle)
            }
            else -> splitBy(ogTitle.replace(Regex("""\s*[|-]\s*Deezer\s*$"""), ""))
        }
        return info.takeIf { it.title.isNotBlank() }
    }

    // Client Credentials flow; needs a Client ID and Secret from developer.spotify.com.
    fun fromSpotifyApi(link: SharedLink, clientId: String, clientSecret: String): TrackInfo? {
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        val trackId = spotifyTrackId(resolveRedirects(link.url)) ?: return null
        val token = runCatching {
            val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
            val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Basic $basic")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("access_token")
        }.onFailure { AppLog.d("spotify token failed: ${it.message}") }.getOrNull() ?: return null
        return runCatching {
            val id = URLEncoder.encode(trackId, "UTF-8")
            val conn = URL("https://api.spotify.com/v1/tracks/$id").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val artists = json.getJSONArray("artists")
            val names = (0 until artists.length()).map { artists.getJSONObject(it).getString("name") }
            TrackInfo(names.joinToString(", "), json.getString("name"))
        }.onFailure { AppLog.d("spotify track failed: ${it.message}") }.getOrNull()
    }

    // The text a music app shares next to the link, minus filler words, as a last-resort query.
    fun fromSharedText(link: SharedLink): TrackInfo? {
        val cleaned = link.extraText
            .replace(Regex("""(?i)\b(listen to|on deezer|on spotify|on apple music|on soundcloud|dinle)\b"""), " ")
            .replace(Regex("""[“”"]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isBlank()) null else TrackInfo("", cleaned)
    }

    private fun spotifyTrackId(url: String): String? {
        val segments = runCatching { URL(url).path.split('/').filter { it.isNotBlank() } }.getOrNull() ?: return null
        val i = segments.indexOf("track")
        return segments.getOrNull(i + 1)?.takeIf { i >= 0 }
    }

    // "Song by Artist" -> artist + title; anything else stays as the title.
    private fun splitBy(text: String): TrackInfo {
        val i = text.lastIndexOf(" by ")
        if (i <= 0) return TrackInfo("", text.trim())
        val title = text.substring(0, i).replace(Regex("""\s[–-]\s(Song|Single|EP)$"""), "").trim()
        return TrackInfo(text.substring(i + 4).trim(), title)
    }

    private fun resolveRedirects(url: String): String = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.inputStream.close()
        conn.url.toString()
    }.getOrDefault(url)

    private fun fetch(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "en")
        conn.inputStream.bufferedReader().use { reader ->
            val buf = CharArray(MAX_HTML)
            var total = 0
            while (total < MAX_HTML) {
                val n = reader.read(buf, total, MAX_HTML - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        }
    }.onFailure { AppLog.d("fetch failed: ${it.message}") }.getOrNull()

    // <meta property|name="key" content="value"> in any attribute order.
    private fun metaTags(html: String): Map<String, String> {
        val attr = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        val result = mutableMapOf<String, String>()
        Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val attrs = attr.findAll(tag.value).associate {
                it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] }
            }
            val key = attrs["property"] ?: attrs["name"] ?: return@forEach
            val content = attrs["content"] ?: return@forEach
            result.putIfAbsent(key, decode(content))
        }
        return result
    }

    private fun unescape(s: String) = runCatching { JSONArray("[\"$s\"]").getString(0) }.getOrDefault(s)

    private fun decode(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
