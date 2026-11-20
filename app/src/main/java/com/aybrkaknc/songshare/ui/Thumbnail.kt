package com.aybrkaknc.songshare.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(Runtime.getRuntime().maxMemory().toInt() / 8) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Null when the image cannot be downloaded; the placeholder stays in that case.
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache[url] ?: runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

@Composable
fun Thumbnail(url: String, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, url) { value = Thumbnails.load(url) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
