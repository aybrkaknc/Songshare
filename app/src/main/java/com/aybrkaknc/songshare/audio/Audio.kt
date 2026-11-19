package com.aybrkaknc.songshare.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

object Audio {
    // Waveform resolution: one peak per 100 ms of audio.
    const val PEAK_MS = 100
    private const val PCM_RATE = 8000
    private const val SAMPLES_PER_PEAK = PCM_RATE * PEAK_MS / 1000

    fun durationMs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }

    // Re-encodes [startMs, endMs) to a 192 kbps MP3; re-encoding keeps the cut sample accurate.
    suspend fun cut(context: Context, input: File, startMs: Long, endMs: Long, output: File) = withContext(Dispatchers.IO) {
        require(endMs > startMs) { "empty range" }
        Ytdl.ensureInit(context)
        output.parentFile?.mkdirs()
        val process = ffmpeg(
            context,
            "-y", "-hide_banner", "-loglevel", "error",
            "-ss", seconds(startMs), "-i", input.absolutePath, "-t", seconds(endMs - startMs),
            "-map", "0:a", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3",
            output.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0 || !output.exists()) throw IOException(log.ifBlank { "ffmpeg failed" })
    }

    // Decodes to 8 kHz mono PCM and keeps the absolute peak of each 100 ms block, normalized to 0..1.
    suspend fun peaks(context: Context, input: File): FloatArray = withContext(Dispatchers.IO) {
        Ytdl.ensureInit(context)
        val process = ffmpeg(
            context,
            "-v", "quiet", "-i", input.absolutePath, "-ac", "1", "-ar", PCM_RATE.toString(), "-f", "s16le", "-",
        ).start()
        val peaks = ArrayList<Float>()
        var peak = 0
        var count = 0
        fun add(low: Int, high: Int) {
            val s = ((high shl 8) or (low and 0xFF)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(s))
            if (++count == SAMPLES_PER_PEAK) {
                peaks.add(peak / 32768f)
                peak = 0
                count = 0
            }
        }
        val buf = ByteArray(8192)
        var carry = -1
        process.inputStream.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                var i = 0
                // A sample can be split across two reads; carry holds its low byte.
                if (carry >= 0 && n > 0) {
                    add(carry, buf[0].toInt())
                    carry = -1
                    i = 1
                }
                while (i + 1 < n) {
                    add(buf[i].toInt(), buf[i + 1].toInt())
                    i += 2
                }
                if (i < n) carry = buf[i].toInt()
            }
        }
        process.waitFor()
        if (count > 0) peaks.add(peak / 32768f)
        val max = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
        FloatArray(peaks.size) { peaks[it] / max }
    }

    // Copies the clip to Download/Songshare. Returns false when the copy fails.
    fun saveToDownloads(context: Context, file: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Songshare")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Songshare")
            dir.mkdirs()
            var target = File(dir, file.name)
            var n = 1
            while (target.exists()) target = File(dir, "${file.nameWithoutExtension} (${n++}).mp3")
            file.copyTo(target)
        }
        true
    }.getOrDefault(false)

    // Characters that are invalid in file names on Android and the systems clips get shared to.
    fun safeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.').take(120).ifBlank { "clip" }

    fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val tenths = (ms % 1000) / 100
        return String.format(Locale.ROOT, "%d:%02d.%d", totalSec / 60, totalSec % 60, tenths)
    }

    // Accepts "m:ss", "m:ss.t" or plain seconds.
    fun parseMs(text: String): Long? {
        val t = text.trim()
        val parts = t.split(':')
        return runCatching {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000).toLong()
                2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
                else -> null
            }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

    private fun ffmpeg(context: Context, vararg args: String): ProcessBuilder {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return ProcessBuilder(listOf(bin.absolutePath) + args).apply {
            environment()["LD_LIBRARY_PATH"] = "$packages/python/usr/lib:$packages/ffmpeg/usr/lib"
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }
}
