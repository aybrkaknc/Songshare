package com.aybrkaknc.songshare.ytdl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// YouTube's own completion service: the best suggestion for a query, or null when there is none.
object Suggest {
    suspend fun first(query: String): String? = withContext(Dispatchers.IO) {
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(body).optJSONArray(1)?.optString(0)?.takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }
}
