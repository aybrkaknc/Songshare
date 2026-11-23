package com.aybrkaknc.songshare.share

import android.content.Context
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.lyrics.LyricsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The trim page's edits: what the share page builds its files from.
// lines are the checked lyric lines with their time in the song (offset applied; null for untimed lyrics).
data class ClipDraft(
    val source: File,
    val start: Long,
    val end: Long,
    val name: String,
    val artist: String,
    val title: String,
    val lines: List<Pair<String, Long?>>,
    val coverUrl: String?,
    // Indices into the song's lyric lines that are checked; kept so the trim page can restore them.
    val selected: Set<Int> = emptySet(),
) {
    // Checked lines that start before the clip ends; only these go into the video.
    val timedLines: List<Pair<String, Long?>> get() = lines.filter { (_, t) -> t != null && t < end }
    val baseName: String get() = Audio.safeFileName(name)
}

enum class ShareKind { VIDEO, MP3, CARD }

object ClipMaker {
    // One slide frame in the lyrics video; matches the video's 10 fps.
    private const val SLIDE_FRAME_MS = 100L

    // Writes one kind into dir as <name>.<ext>. The video leaves the clip MP3 next to it.
    suspend fun produce(context: Context, draft: ClipDraft, kind: ShareKind, dir: File): File {
        dir.mkdirs()
        val mp3 = File(dir, draft.baseName + ".mp3")
        return when (kind) {
            ShareKind.MP3 -> mp3.also { Audio.cut(context, draft.source, draft.start, draft.end, it) }
            ShareKind.VIDEO -> {
                Audio.cut(context, draft.source, draft.start, draft.end, mp3)
                File(dir, draft.baseName + ".mp4").also { buildVideo(context, draft, mp3, it) }
            }
            ShareKind.CARD -> File(dir, draft.baseName + ".png").also {
                val cover = LyricsCard.loadCover(draft.coverUrl)
                withContext(Dispatchers.Default) {
                    LyricsCard.renderCard(cover, draft.artist, draft.title.ifBlank { draft.name }, draft.lines.map { l -> l.first }, it)
                }
            }
        }
    }

    // Each checked line is current from its own time until the next checked line (the last one until the clip ends).
    // The highlight switches exactly at the line's time; the window then slides up over two short frames, so the motion
    // never moves the timing.
    private suspend fun buildVideo(context: Context, draft: ClipDraft, audio: File, out: File) {
        val lines = draft.timedLines
        val length = draft.end - draft.start
        val bounds = lines.mapIndexed { i, (_, t) -> if (i == 0) 0L else ((t ?: 0L) - draft.start).coerceIn(0L, length) }
        // (line index, slide progress, duration in ms) for every frame in order.
        val steps = mutableListOf<Triple<Int, Float, Long>>()
        lines.indices.forEach { i ->
            val segment = (bounds.getOrNull(i + 1) ?: length) - bounds[i]
            if (segment <= 0) return@forEach
            var used = 0L
            if (i > 0 && segment > 3 * SLIDE_FRAME_MS) {
                steps += Triple(i, 1f / 3, SLIDE_FRAME_MS)
                steps += Triple(i, 2f / 3, SLIDE_FRAME_MS)
                used = 2 * SLIDE_FRAME_MS
            }
            steps += Triple(i, 1f, segment - used)
        }
        val texts = lines.map { it.first }
        val frameDir = File(out.parentFile, "frames").apply { deleteRecursively(); mkdirs() }
        try {
            val cover = LyricsCard.loadCover(draft.coverUrl)
            val frames = withContext(Dispatchers.Default) {
                steps.mapIndexed { n, (i, progress, _) ->
                    File(frameDir, "f$n.png").also { f ->
                        LyricsCard.renderFrame(cover, draft.artist, draft.title.ifBlank { draft.name }, texts, i, progress, f)
                    }
                }
            }
            Audio.makeVideo(context, frames, steps.map { it.third }, audio, out)
        } finally {
            frameDir.deleteRecursively()
        }
    }
}
