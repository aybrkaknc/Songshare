package com.aybrkaknc.songshare.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.AppLog
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Session
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.cover.CoverArt
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.share.ClipDraft
import com.aybrkaknc.songshare.share.ClipMaker
import com.aybrkaknc.songshare.share.ShareKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MIN_CLIP_MS = 100L
private val WhatsAppGreen = Color(0xFF25D366)
private const val LOOKUP_DELAY_MS = 600L

private fun nameFor(artist: String, title: String) = if (artist.isBlank()) title else "$artist - $title"

private fun formatLabel(kind: ShareKind): Int = when (kind) {
    ShareKind.VIDEO -> R.string.kind_video
    ShareKind.MP3 -> R.string.kind_mp3
    ShareKind.CARD -> R.string.kind_card
}

// Editing only. The file name, waveform and times stay put; the lyrics take the rest of the height and scroll on their own.
// "Paylaş" in the bottom right saves the clip (and the lyrics video when lines are timed) and opens the share page.
@Composable
fun TrimScreen(step: Step.Trim, onShare: (ClipDraft, Map<ShareKind, File>, ShareKind) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val duration = step.durationMs

    // Coming back from the share page puts the earlier edits back.
    val resume = remember(step.file) { Session.draft?.takeIf { it.source == step.file } }
    var start by rememberSaveable { mutableLongStateOf(resume?.start ?: 0L) }
    var end by rememberSaveable { mutableLongStateOf(resume?.end ?: duration) }
    var name by rememberSaveable { mutableStateOf(resume?.name ?: step.name) }
    var artist by rememberSaveable { mutableStateOf(resume?.artist ?: step.artist) }
    var title by rememberSaveable { mutableStateOf(resume?.title ?: step.title) }
    var naming by remember { mutableStateOf(false) }
    var lyricLines by remember { mutableStateOf(resume?.lines.orEmpty()) }
    var selectedLines by remember { mutableStateOf(resume?.selected.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    // The play position line appears after a tap on the waveform or a lyric line, or once playback starts.
    var playheadVisible by remember { mutableStateOf(false) }
    // The play position: moves while playing, stays where it stopped.
    var playhead by remember { mutableFloatStateOf((resume?.start ?: 0L).toFloat()) }

    // Album art from MusicBrainz as the names settle; the YouTube thumbnail stays as the fallback.
    val coverUrl by produceState(step.thumb, artist, title) {
        delay(LOOKUP_DELAY_MS)
        value = CoverArt.find(artist, title) ?: step.thumb
    }

    val peaks by produceState<FloatArray?>(null, step.file) {
        value = runCatching { Audio.peaks(context, step.file) }.getOrDefault(FloatArray(0))
    }

    val player = remember(step.file) {
        runCatching { MediaPlayer().apply { setDataSource(step.file.absolutePath); prepare() } }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    // Follows the player and stops playback at the end edge (MediaPlayer has no built-in end position).
    // At the end the play position goes back to the clip start, like a player that reached the end.
    LaunchedEffect(playing) {
        while (playing && player != null) {
            val pos = player.currentPosition.toLong()
            playhead = pos.toFloat()
            if (pos >= end || !player.isPlaying) {
                player.pause()
                playing = false
                if (pos >= end) playhead = start.toFloat()
            }
            delay(40)
        }
    }

    fun stopPlayback() {
        if (playing) player?.pause()
        playing = false
    }

    fun draft() = ClipDraft(step.file, start, end, name, artist, title, lyricLines, coverUrl, selectedLines)

    // All formats are always listed. Video needs timed lyric lines and the card needs any lyric line; until then they are grey.
    val allFormats = listOf(ShareKind.VIDEO, ShareKind.MP3, ShareKind.CARD)
    fun available(k: ShareKind) = when (k) {
        ShareKind.VIDEO -> draft().timedLines.isNotEmpty()
        ShareKind.MP3 -> true
        ShareKind.CARD -> lyricLines.isNotEmpty()
    }
    var format by rememberSaveable { mutableStateOf<ShareKind?>(null) }
    var needLyrics by remember { mutableStateOf(false) }
    val chosen = format?.takeIf { available(it) } ?: ShareKind.MP3

    // Saves the clip MP3 (and the video when lines are timed) to Edited, then opens the share page with those files.
    fun goShare() {
        stopPlayback()
        saving = true
        scope.launch {
            val edits = draft()
            val dir = File(context.cacheDir, "share").apply { deleteRecursively() }
            val video = edits.timedLines.isNotEmpty()
            val files = runCatching {
                if (video) {
                    val mp4 = ClipMaker.produce(context, edits, ShareKind.VIDEO, dir)
                    mapOf(ShareKind.VIDEO to mp4, ShareKind.MP3 to File(dir, edits.baseName + ".mp3"))
                } else {
                    mapOf(ShareKind.MP3 to ClipMaker.produce(context, edits, ShareKind.MP3, dir))
                }
            }.onFailure { AppLog.d("share files failed: ${it.message}") }.getOrNull()
            if (files == null) {
                saving = false
                Toast.makeText(context, if (video) R.string.video_failed else R.string.cut_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                files.values.map { Audio.saveClip(context, it, it.name, Audio.EDITED, prefs.saveDir) }.all { it }
            }
            if (!saved) Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show()
            saving = false
            onShare(edits, files, chosen)
        }
    }

    // The header title asks for the name window.
    LaunchedEffect(Unit) { Session.renameRequests.collect { naming = true } }

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        val wave = peaks
        if (wave == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
        } else {
            Waveform(
                wave,
                duration,
                start,
                end,
                playhead,
                MIN_CLIP_MS,
                onRangeChange = { s, e ->
                    start = s
                    end = e
                },
                onSeek = { ms ->
                    // A tap on the waveform only moves the play position, kept inside the clip.
                    val to = ms.coerceIn(start, end)
                    playhead = to.toFloat()
                    playheadVisible = true
                    if (playing && player != null) player.seekTo(to, MediaPlayer.SEEK_CLOSEST)
                },
                playing = playing,
                canPlay = player != null,
                onPlayToggle = {
                    if (playing) {
                        stopPlayback()
                    } else if (player != null) {
                        // Plays on from where it stopped; from the clip start when that is outside the clip.
                        val from = playhead.toLong().takeIf { it in start until end } ?: start
                        player.seekTo(from, MediaPlayer.SEEK_CLOSEST)
                        player.start()
                        playing = true
                        playheadVisible = true
                    }
                },
                playheadVisible = playheadVisible,
            )
        }
        Spacer(Modifier.height(4.dp))

        LyricsArea(
            artist = artist,
            title = title,
            durationMs = duration,
            playheadMs = playhead.toLong(),
            startMs = start,
            onRange = { s, e ->
                start = s
                end = e.coerceIn(s + MIN_CLIP_MS, duration)
            },
            onStart = { s ->
                // A tapped lyric line only moves the play position; the clip edges stay where they are.
                val to = s.coerceIn(start, end)
                playhead = to.toFloat()
                playheadVisible = true
                if (playing && player != null) player.seekTo(to, MediaPlayer.SEEK_CLOSEST)
            },
            onSelectedText = { lyricLines = it },
            initialSelected = resume?.selected.orEmpty(),
            onSelectionChange = { selectedLines = it },
            modifier = Modifier.weight(1f),
        )

        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        // Format chips on the left, the share button on the right, on one line.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                allFormats.forEach { k ->
                    val ready = available(k)
                    FilterChip(
                        selected = chosen == k,
                        onClick = {
                            if (ready) format = k else needLyrics = true
                        },
                        label = {
                            Text(
                                stringResource(formatLabel(k)),
                                color = if (ready) Color.Unspecified else MaterialTheme.colorScheme.outline,
                            )
                        },
                    )
                }
            }
            Button(
                onClick = ::goShare,
                enabled = !saving && name.isNotBlank() && end - start >= MIN_CLIP_MS,
                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = Color.Black),
            ) {
                Text(stringResource(R.string.share_next))
            }
        }
    }

    if (needLyrics) {
        AppAlertDialog(
            onDismissRequest = { needLyrics = false },
            text = { Text(stringResource(R.string.pick_lyrics_first)) },
            confirmButton = {
                TextButton(onClick = { needLyrics = false }) { Text(stringResource(R.string.done)) }
            },
        )
    }

    if (naming) {
        NameDialog(artist, title, name, onDismiss = { naming = false }) { a, t, n ->
            artist = a
            title = t
            name = n
            naming = false
        }
    }
}

// Artist, title and file name. The file name follows the other two until it is typed in by hand.
// Tamam applies the changes; İptal and a tap outside keep the old values.
@Composable
private fun NameDialog(artist: String, title: String, name: String, onDismiss: () -> Unit, onDone: (String, String, String) -> Unit) {
    var a by remember { mutableStateOf(artist) }
    var t by remember { mutableStateOf(title) }
    var n by remember { mutableStateOf(name) }
    var custom by remember { mutableStateOf(name != nameFor(artist, title)) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_details)) },
        text = {
            Column {
                OutlinedTextField(
                    value = a,
                    onValueChange = {
                        a = it
                        if (!custom) n = nameFor(a, t)
                    },
                    label = { Text(stringResource(R.string.artist)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = t,
                    onValueChange = {
                        t = it
                        if (!custom) n = nameFor(a, t)
                    },
                    label = { Text(stringResource(R.string.song_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = n,
                    onValueChange = {
                        n = it
                        custom = true
                    },
                    label = { Text(stringResource(R.string.file_name)) },
                    suffix = { Text(".mp3") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(a, t, n) }, enabled = n.isNotBlank()) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
