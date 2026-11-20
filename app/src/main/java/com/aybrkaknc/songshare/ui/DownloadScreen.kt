package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Song
import com.aybrkaknc.songshare.Step

// The page while a song downloads: the picture and name, the status and the bar, centered in the page.
// A link has no picture, so only its name is shown.
@Composable
fun DownloadScreen(step: Step.Downloading, song: Song?) {
    val picked = step.picked
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picked != null) {
                Thumbnail(picked.thumb, Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(16.dp))
            }
            Text(
                picked?.title ?: song?.label.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                when {
                    step.converting -> stringResource(R.string.converting)
                    step.progress >= 0 -> stringResource(R.string.progress_percent, step.progress.toInt())
                    else -> stringResource(R.string.preparing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            val bar = Modifier.fillMaxWidth()
            if (step.progress < 0) LinearProgressIndicator(bar)
            else LinearProgressIndicator(progress = { step.progress / 100f }, modifier = bar)
        }
    }
}
