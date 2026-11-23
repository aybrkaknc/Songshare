package com.aybrkaknc.songshare.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aybrkaknc.songshare.AppLog
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.share.ClipMaker
import com.aybrkaknc.songshare.share.ShareKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val WHATSAPP = "com.whatsapp"
private val WhatsAppGreen = Color(0xFF25D366)

// Picks the format (video, MP3 or card), shows it, and sends it to WhatsApp or through the system share panel.
// The MP3 and the video were made and saved on the trim page; the card is drawn here when it is picked.
@Composable
fun ShareScreen(step: Step.Share, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val kinds = buildList {
        if (ShareKind.VIDEO in step.files) add(ShareKind.VIDEO)
        add(ShareKind.MP3)
        if (step.draft.lines.isNotEmpty()) add(ShareKind.CARD)
    }
    var kind by remember { mutableStateOf(if (step.initial in kinds) step.initial else kinds.first()) }
    var sending by remember { mutableStateOf(false) }
    val card by produceState<File?>(null, kind) {
        if (kind != ShareKind.CARD) return@produceState
        val dir = File(context.cacheDir, "share/card").apply { deleteRecursively() }
        value = runCatching { ClipMaker.produce(context, step.draft, ShareKind.CARD, dir) }
            .onFailure { AppLog.d("card failed: ${it.message}") }
            .getOrNull()
    }
    val file = if (kind == ShareKind.CARD) card else step.files[kind]

    // The card is saved to Edited when it is sent; the MP3 and the video were saved before this page opened.
    fun send(whatsapp: Boolean, file: File) {
        sending = true
        scope.launch {
            if (kind == ShareKind.CARD) {
                val saved = withContext(Dispatchers.IO) { Audio.saveClip(context, file, file.name, Audio.EDITED, prefs.saveDir) }
                if (!saved) Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
            sending = false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val mime = when (file.extension) {
                "mp4" -> "video/mp4"
                "png" -> "image/png"
                else -> "audio/mpeg"
            }
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(file.name, uri)
            val started = if (whatsapp) {
                try {
                    context.startActivity(intent.setPackage(WHATSAPP))
                    true
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.whatsapp_missing, Toast.LENGTH_LONG).show()
                    false
                }
            } else {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_with)))
                true
            }
            if (started) onDone()
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { kind = k },
                    label = { Text(stringResource(kindLabel(k))) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Every preview carries the clip length in its bottom right corner.
        val length = Audio.formatMs(step.draft.end - step.draft.start)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                file == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                kind == ShareKind.VIDEO -> key(file.absolutePath) { VideoPreview(file, length) }
                kind == ShareKind.CARD -> CardPreview(file, length)
                else -> Mp3Preview(file, length)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (sending) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { file?.let { send(whatsapp = true, it) } },
                enabled = file != null && !sending,
                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.share_whatsapp))
            }
            Button(
                onClick = { file?.let { send(whatsapp = false, it) } },
                enabled = file != null && !sending,
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

private fun kindLabel(kind: ShareKind): Int = when (kind) {
    ShareKind.VIDEO -> R.string.kind_video
    ShareKind.MP3 -> R.string.kind_mp3
    ShareKind.CARD -> R.string.kind_card
}
