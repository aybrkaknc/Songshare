package com.aybrkaknc.songshare.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.settings.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val MIN_CLIP_MS = 100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(step: Step.Trim, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val duration = step.durationMs

    var start by rememberSaveable { mutableLongStateOf(0L) }
    var end by rememberSaveable { mutableLongStateOf(duration) }
    var name by rememberSaveable { mutableStateOf(step.name) }
    var cutting by remember { mutableStateOf(false) }
    var lastClip by remember { mutableStateOf<File?>(null) }
    var askSave by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableFloatStateOf(-1f) }

    val peaks by produceState<FloatArray?>(null, step.file) {
        value = runCatching { Audio.peaks(context, step.file) }.getOrDefault(FloatArray(0))
    }

    val player = remember(step.file) {
        runCatching { MediaPlayer().apply { setDataSource(step.file.absolutePath); prepare() } }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }

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

    fun save(file: File) {
        val ok = Audio.saveToDownloads(context, file)
        Toast.makeText(context, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val file = lastClip
        if (granted && file != null) save(file)
        else Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show()
    }

    fun saveWithPermission(file: File) {
        if (Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(file)
        }
    }

    // Called when the user comes back from the share sheet, whether or not something was sent.
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = lastClip ?: return@rememberLauncherForActivityResult
        if (prefs.alwaysSave) saveWithPermission(file) else askSave = true
    }

    fun share() {
        stopPreview()
        cutting = true
        scope.launch {
            val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, Audio.safeFileName(name) + ".mp3")
            val result = runCatching { Audio.cut(context, step.file, start, end, out) }
            cutting = false
            if (result.isFailure) {
                Toast.makeText(context, R.string.cut_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            lastClip = out
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(out.name, uri)
            shareLauncher.launch(Intent.createChooser(send, context.getString(R.string.share_with)))
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        TopRow(stringResource(R.string.trim_title), stringResource(R.string.back)) {
            stopPreview()
            onBack()
        }

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
            Waveform(wave, duration, start, end, playhead)
        }

        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { r ->
                if (r.endInclusive - r.start >= MIN_CLIP_MS) {
                    start = r.start.toLong()
                    end = r.endInclusive.toLong()
                }
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

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
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (playing) R.string.stop else R.string.preview)) }
            Button(
                onClick = ::share,
                enabled = !cutting && name.isNotBlank() && end - start >= MIN_CLIP_MS,
                modifier = Modifier.weight(1f),
            ) {
                if (cutting) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.share))
            }
        }
    }

    if (askSave) {
        var always by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(stringResource(R.string.save_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Text(stringResource(R.string.always_save))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askSave = false
                    if (always) prefs.alwaysSave = true
                    lastClip?.let(::saveWithPermission)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { askSave = false }) { Text(stringResource(R.string.dont_save)) }
            },
        )
    }
}

// Accepts m:ss.t; the new value is applied on Done and the field resets when it is invalid.
@Composable
private fun TimeField(label: String, valueMs: Long, modifier: Modifier, onCommit: (Long?) -> Unit) {
    var text by remember(valueMs) { mutableStateOf(Audio.formatMs(valueMs)) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onCommit(Audio.parseMs(text))
            text = Audio.formatMs(valueMs)
        }),
        modifier = modifier,
    )
}

@Composable
private fun Waveform(peaks: FloatArray, durationMs: Long, start: Long, end: Long, playhead: Float) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 10.dp)) {
        if (peaks.isEmpty() || durationMs <= 0) return@Canvas
        val bars = minOf(peaks.size, (size.width / 3.dp.toPx()).toInt()).coerceAtLeast(1)
        val barW = size.width / bars
        for (b in 0 until bars) {
            val from = b * peaks.size / bars
            val to = maxOf(from + 1, (b + 1) * peaks.size / bars)
            var v = 0f
            for (i in from until to) v = maxOf(v, peaks[i])
            val x = b * barW
            val t = (x + barW / 2) / size.width * durationMs
            val h = maxOf(2.dp.toPx(), v * size.height)
            drawRect(
                color = if (t >= start && t <= end) AppColors.Accent else AppColors.Wave,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barW * 0.7f, h),
            )
        }
        if (playhead >= 0) {
            val x = playhead / durationMs * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
