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

    // Joins still frames (each shown for its duration) with the clip audio into an MP4.
    // H.264 is tried first; builds without libx264 fall back to MPEG-4 Part 2.
    suspend fun makeVideo(context: Context, frames: List<File>, durationsMs: List<Long>, audio: File, output: File) = withContext(Dispatchers.IO) {
        require(frames.isNotEmpty() && frames.size == durationsMs.size) { "no frames" }
        Ytdl.ensureInit(context)
        val list = File(output.parentFile, "frames.txt")
        list.writeText(buildString {
            frames.zip(durationsMs).forEach { (f, d) ->
                append("file '").append(f.absolutePath).append("'\n")
                append("duration ").append(seconds(d)).append('\n')
            }
            // The concat reader ignores the last duration unless the last file is listed once more.
            append("file '").append(frames.last().absolutePath).append("'\n")
        })
        var log = ""
        for (codec in listOf(listOf("-c:v", "libx264", "-tune", "stillimage", "-preset", "veryfast"), listOf("-c:v", "mpeg4", "-q:v", "3"))) {
            val process = ffmpeg(
                context,
                *(listOf("-y", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.absolutePath, "-i", audio.absolutePath) +
                    codec +
                    listOf("-r", "10", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-shortest", "-movflags", "+faststart", output.absolutePath)
                    ).toTypedArray(),
            ).redirectErrorStream(true).start()
            log = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0 && output.exists() && output.length() > 0) {
                AppLog.d("video codec: ${codec[1]}")
                return@withContext
            }
            AppLog.d("video ${codec[1]} failed: $log")
            output.delete()
        }
        throw IOException(log.ifBlank { "ffmpeg failed" })
    }

    // Converts a downloaded stream that WhatsApp cannot play (e.g. webm/opus) to a 192 kbps MP3.
    suspend fun toMp3(context: Context, input: File, output: File) = withContext(Dispatchers.IO) {
        Ytdl.ensureInit(context)
        val process = ffmpeg(
            context,
            "-y", "-hide_banner", "-loglevel", "error",
            "-i", input.absolutePath, "-map", "0:a", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3",
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

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        else -> "audio/mpeg"
    }

    // A file in the Unedited folder whose tags carry this YouTube id, copied into the download folder; null when there is none.
    // Newest files are checked first. Files saved before ids were written do not match, so those songs are downloaded again.
    suspend fun findSavedById(context: Context, id: String, treeUri: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            Ytdl.ensureInit(context)
            val dir = File(context.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            savedCandidates(context, treeUri).firstNotNullOfOrNull { (name, open) ->
                val copy = File(dir, name)
                val copied = open()?.use { input -> copy.outputStream().use { input.copyTo(it) } } != null
                if (copied && tagsContain(context, copy, id)) copy else null.also { copy.delete() }
            }
        }.onFailure { AppLog.d("saved lookup failed: ${it.message}") }.getOrNull()
    }

    // (display name, opener) for every file in the Unedited folder.
    private fun savedCandidates(context: Context, treeUri: String): List<Pair<String, () -> java.io.InputStream?>> {
        val resolver = context.contentResolver
        if (treeUri.isNotBlank()) {
            val tree = Uri.parse(treeUri)
            val folder = clipDir(resolver, tree, UNEDITED)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            return buildList {
                resolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        add(c.getString(1) to { resolver.openInputStream(doc) })
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val where = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf("${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER/$UNEDITED/")
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            return buildList {
                resolver.query(collection, columns, where, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                        add(c.getString(1) to { resolver.openInputStream(uri) })
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$ROOT_FOLDER/$UNEDITED")
        return dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }.map { f -> f.name to { f.inputStream() } }
    }

    // ffmpeg prints a file's tags when it is given no output; the id is looked for anywhere in them (comment or source URL).
    private fun tagsContain(context: Context, file: File, id: String): Boolean {
        val process = ffmpeg(context, "-hide_banner", "-i", file.absolutePath).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return log.contains(id)
    }

    // Removes a file with this name from the clip folder, so saving the same clip again replaces it instead of piling up copies.
    private fun deleteExisting(context: Context, displayName: String, folder: String, treeUri: String) {
        val resolver = context.contentResolver
        val video = mimeFor(displayName).startsWith("video/")
        val top = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        if (treeUri.isNotBlank()) {
            val tree = Uri.parse(treeUri)
            val dir = clipDir(resolver, tree, folder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val matches = mutableListOf<Uri>()
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == displayName) matches += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
            matches.forEach { DocumentsContract.deleteDocument(resolver, it) }
        } else if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val where = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(displayName, "$top/$ROOT_FOLDER/$folder/")
            val ids = mutableListOf<Long>()
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), where, args, null)?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            ids.forEach { resolver.delete(android.content.ContentUris.withAppendedId(collection, it), null, null) }
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(top), "$ROOT_FOLDER/$folder/$displayName").delete()
        }
    }

    // Copies a file into a clip folder under the root. An older file with the same name is replaced.
    // Returns false when the copy fails.
    fun saveClip(context: Context, file: File, displayName: String, folder: String, treeUri: String): Boolean = runCatching {
        // Folders deleted by hand are created again before every save.
        if (treeUri.isBlank()) ensureDefaultFolders()
        runCatching { deleteExisting(context, displayName, folder, treeUri) }
            .onFailure { AppLog.d("replace failed: ${it.message}") }
        if (treeUri.isNotBlank()) {
            saveToTree(context, file, displayName, folder, Uri.parse(treeUri))
        } else if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore keeps videos out of Music, so a video goes to Movies/Songshare/<folder> instead.
            val video = mimeFor(displayName).startsWith("video/")
            val top = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$top/$ROOT_FOLDER/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val top = if (mimeFor(displayName).startsWith("video/")) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            val dir = File(Environment.getExternalStoragePublicDirectory(top), "$ROOT_FOLDER/$folder")
            dir.mkdirs()
            file.copyTo(File(dir, displayName), overwrite = true)
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
        val doc = DocumentsContract.createDocument(resolver, dir, mimeFor(displayName), displayName) ?: error("no document")
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
