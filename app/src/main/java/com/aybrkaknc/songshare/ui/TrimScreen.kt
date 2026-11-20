package com.aybrkaknc.songshare.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aybrkaknc.songshare.AppLog
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MIN_CLIP_MS = 100L
private const val WHATSAPP = "com.whatsapp"

private val WhatsAppGreen = Color(0xFF25D366)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(step: Step.Trim, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val duration = step.durationMs

    var start by rememberSaveable { mutableLongStateOf(0L) }
    var end by rememberSaveable { mutableLongStateOf(duration) }
    var name by rememberSaveable { mutableStateOf(step.name) }
    var cutting by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableFloatStateOf(-1f) }

    val peaks by produceState<FloatArray?>(null, step.file) {
        value = runCatching { Audio.peaks(context, step.file) }.getOrDefault(FloatArray(0))
    }

    val player = remember(step.file) {
        runCatching { MediaPlayer().apply { setDataSource(step.file.absolutePath); prepare() } }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    // Moving the start edge while previewing restarts the preview from the new start.
    LaunchedEffect(start) {
        if (playing && player != null) player.seekTo(start, MediaPlayer.SEEK_CLOSEST)
    }

    // Stops the preview at the end handle; MediaPlayer has no built-in end position.
    LaunchedEffect(playing) {
        while (playing && player != null) {
            val pos = player.currentPosition.toLong()
            playhead = pos.toFloat()
            if (pos >= end || !player.isPlaying) {
                player.pause()
                playing = false
            }
            delay(40)
        }
        playhead = -1f
    }

    fun stopPreview() {
        if (playing) player?.pause()
        playing = false
    }

    // Cuts the clip, keeps the edited copy in Edited, and opens the share target. The copy is kept as soon as a share button is pressed.
    // WhatsApp opens its own contact list directly; the other button goes through the system share panel.
    fun share(whatsapp: Boolean) {
        stopPreview()
        cutting = true
        scope.launch {
            val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val fileName = Audio.safeFileName(name) + ".mp3"
            val out = File(dir, fileName)
            val result = runCatching { Audio.cut(context, step.file, start, end, out) }
            if (result.isFailure) {
                cutting = false
                Toast.makeText(context, R.string.cut_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { Audio.saveClip(context, out, fileName, Audio.EDITED, prefs.saveDir) }
            if (!saved) Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show()
            cutting = false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(out.name, uri)
            if (whatsapp) {
                try {
                    context.startActivity(send.setPackage(WHATSAPP))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.whatsapp_missing, Toast.LENGTH_LONG).show()
                }
            } else {
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share_with)))
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.file_name)) },
            suffix = { Text(".mp3") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

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
                onSeek = { ms ->
                    // A tap only moves the preview while it plays; the point is kept inside the clip.
                    if (playing && player != null) player.seekTo(ms.coerceIn(start, end), MediaPlayer.SEEK_CLOSEST)
                },
            ) { s, e ->
                start = s
                end = e
            }
        }
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField(stringResource(R.string.start), start, Modifier.weight(1f)) { v ->
                if (v != null && v <= end - MIN_CLIP_MS) start = v
            }
            TimeField(stringResource(R.string.end), end, Modifier.weight(1f)) { v ->
                if (v != null && v >= start + MIN_CLIP_MS) end = v.coerceAtMost(duration)
            }
        }
        Text(
            stringResource(R.string.clip_length, Audio.formatMs(end - start)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                if (playing) {
                    stopPreview()
                } else if (player != null) {
                    player.seekTo(start, MediaPlayer.SEEK_CLOSEST)
                    player.start()
                    playing = true
                }
            },
            enabled = player != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playing) R.string.stop else R.string.preview))
            }
        }

        val canShare = !cutting && name.isNotBlank() && end - start >= MIN_CLIP_MS
        if (cutting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { share(whatsapp = true) },
                enabled = canShare,
                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.share_whatsapp))
            }
            Button(
                onClick = { share(whatsapp = false) },
                enabled = canShare,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.share))
            }
        }
    }
}
