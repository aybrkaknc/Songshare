package com.aybrkaknc.songshare.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aybrkaknc.songshare.R
import java.io.File

// Small dark label in a preview's bottom right corner (the clip length).
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// Plays the video on a loop; the button below pauses and resumes it.
@Composable
internal fun VideoPreview(file: File, badge: String? = null) {
    var view by remember(file) { mutableStateOf<VideoView?>(null) }
    var playing by remember(file) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.start()
                        }
                        view = this
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            badge?.let { Badge(it, Modifier.align(Alignment.BottomEnd)) }
        }
        OutlinedButton(
            onClick = {
                if (playing) view?.pause() else view?.start()
                playing = !playing
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(if (playing) R.string.stop else R.string.preview))
        }
    }
}

@Composable
internal fun CardPreview(file: File, badge: String? = null) {
    val bitmap = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    Box(Modifier.fillMaxWidth()) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth()) }
        badge?.let { Badge(it, Modifier.align(Alignment.BottomEnd)) }
    }
}

@Composable
internal fun Mp3Preview(file: File, badge: String? = null) {
    var playing by remember(file) { mutableStateOf(false) }
    val player = remember(file) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { playing = false }
            }
        }.getOrNull()
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                if (playing) {
                    player?.pause()
                    playing = false
                } else {
                    player?.start()
                    playing = true
                }
            },
            enabled = player != null,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playing) R.string.stop else R.string.preview))
            }
        }
        badge?.let { Badge(it) }
    }
}
