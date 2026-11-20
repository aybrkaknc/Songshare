package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Song
import com.aybrkaknc.songshare.Step

// Panel title row: a back arrow on sub pages, the settings gear on the main page.
@Composable
fun PanelHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onFolder: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onCancel != null) {
            IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel)) }
        }
        if (onFolder != null) {
            IconButton(onClick = onFolder) { Icon(painterResource(R.drawable.ic_folder), stringResource(R.string.open_folder)) }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings)) }
        }
    }
}

// The song in progress with its status; X cancels it.
@Composable
fun ActiveCard(step: Step, active: Song, onCancel: () -> Unit) {
    val title = when {
        step is Step.Trim -> step.name
        step is Step.Search && step.info != null -> step.info.query
        else -> active.label
    }
    val status = when (step) {
        is Step.Search -> stringResource(R.string.status_pick)
        is Step.Downloading -> if (step.progress < 0) stringResource(R.string.downloading)
        else stringResource(R.string.downloading_percent, step.progress.toInt())
        is Step.Trim -> stringResource(R.string.status_trim)
        is Step.Failed -> stringResource(R.string.status_failed)
        Step.Home -> ""
    }
    val progress = when (step) {
        is Step.Downloading -> step.progress
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SongRow(title, status, progress, onCancel)
    }
}

@Composable
private fun SongRow(title: String, status: String, progress: Float?, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {
                    val bar = Modifier.fillMaxWidth().padding(top = 6.dp)
                    if (progress < 0) LinearProgressIndicator(bar)
                    else LinearProgressIndicator(progress = { progress / 100f }, modifier = bar)
                }
            }
            IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.remove)) }
        }
    }
}

// Shown instead of the panel content until notifications are allowed; work never starts without them.
@Composable
fun PermissionCard(onAllow: () -> Unit, onSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.perm_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) { Text(stringResource(R.string.perm_allow)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.perm_settings)) }
            }
        }
    }
}
