package com.aybrkaknc.songshare.audio

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import com.aybrkaknc.songshare.AppLog
import com.aybrkaknc.songshare.R
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

    // Clips go to Unedited (as downloaded) and Edited (as trimmed and shared), under the root folder.
    // The default root is Music/Songshare; a folder the user picked is a SAF tree URI with the same two subfolders.
    const val ROOT_FOLDER = "Songshare"
    const val UNEDITED = "Unedited"
    const val EDITED = "Edited"

    // Creates the default folders up front, so they exist before the first clip. Quietly does nothing if the system refuses.
    fun ensureDefaultFolders(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ROOT_FOLDER)
        File(root, UNEDITED).mkdirs() && File(root, EDITED).mkdirs()
    }.getOrDefault(false)

    // Copies a file into a clip folder under the root. Returns false when the copy fails.
    fun saveClip(context: Context, file: File, displayName: String, folder: String, treeUri: String): Boolean = runCatching {
        // Folders deleted by hand are created again before every save.
        if (treeUri.isBlank()) ensureDefaultFolders()
        if (treeUri.isNotBlank()) {
            saveToTree(context, file, displayName, folder, Uri.parse(treeUri))
        } else if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$ROOT_FOLDER/$folder")
            dir.mkdirs()
            var target = File(dir, displayName)
            var n = 1
            while (target.exists()) target = File(dir, "${target.nameWithoutExtension.substringBefore(" (")} (${n++}).mp3")
            file.copyTo(target)
        }
        true
    }.onFailure { AppLog.d("save failed: ${it.message}") }.getOrDefault(false)

    // Songshare/<folder> inside the picked folder, each level created when it is missing.
    private fun clipDir(resolver: ContentResolver, tree: Uri, folder: String): Uri {
        val songshare = childDir(resolver, tree, DocumentsContract.getTreeDocumentId(tree), ROOT_FOLDER)
        return childDir(resolver, tree, DocumentsContract.getDocumentId(songshare), folder)
    }

    private fun saveToTree(context: Context, file: File, displayName: String, folder: String, tree: Uri) {
        val resolver = context.contentResolver
        val dir = clipDir(resolver, tree, folder)
        val doc = DocumentsContract.createDocument(resolver, dir, "audio/mpeg", displayName) ?: error("no document")
        resolver.openOutputStream(doc)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
    }

    // Returns the subfolder with this name, creating it when it is not there yet.
    fun childDir(resolver: ContentResolver, tree: Uri, parentId: String, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("no folder")
    }

    // Makes Songshare, then Unedited and Edited inside it, in the folder the user picked. Run again to restore deleted ones.
    fun ensureTreeFolders(context: Context, treeUri: String): Boolean = runCatching {
        val tree = Uri.parse(treeUri)
        clipDir(context.contentResolver, tree, UNEDITED)
        clipDir(context.contentResolver, tree, EDITED)
        true
    }.onFailure { AppLog.d("folders failed: ${it.message}") }.getOrDefault(false)

    // Opens the save folder in the system file manager.
    fun openFolder(context: Context, treeUri: String) {
        if (treeUri.isNotBlank()) ensureTreeFolders(context, treeUri) else ensureDefaultFolders()
        val uri = if (treeUri.isNotBlank()) {
            val tree = Uri.parse(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } else {
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2F$ROOT_FOLDER")
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, R.string.no_folder_app, Toast.LENGTH_SHORT).show() }
    }

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
