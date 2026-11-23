package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Song
import com.aybrkaknc.songshare.Step

// Panel title row: a back arrow on sub pages, the settings gear on the main page.
@Composable
fun PanelHeader(
    title: String,
    onTitle: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onFolder: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    centerTitle: Boolean = false,
    // The settings gear sits at the start (left) instead of the end, as on the home page.
    settingsAtStart: Boolean = false,
) {
    // The icons sit in 36 dp boxes, so 14 dp on the right puts the glyphs 20 dp from the edge like the page content.
    val startGear = settingsAtStart && onSettings != null
    val startSlot = if (onBack != null || startGear) 36 else 12
    val endSlots = listOfNotNull(onCancel, onFolder, if (startGear) null else onSettings).size
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            onBack != null -> HeaderButton(onBack, R.drawable.ic_back, R.string.back)
            startGear -> HeaderButton(onSettings!!, R.drawable.ic_settings, R.string.settings)
            else -> Spacer(Modifier.width(12.dp))
        }
        // Centred on the screen: the slots on each side can differ in width, so the title is pushed by the difference.
        val shift = if (centerTitle) maxOf(0, (14 + 36 * endSlots) - (8 + startSlot)) else 0
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .weight(1f)
                .padding(start = shift.dp)
                .then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier),
        )
        if (onCancel != null) HeaderButton(onCancel, R.drawable.ic_close, R.string.cancel)
        if (onFolder != null) HeaderButton(onFolder, R.drawable.ic_folder, R.string.open_folder)
        if (!startGear && onSettings != null) HeaderButton(onSettings, R.drawable.ic_settings, R.string.settings)
    }
}

// A 36 dp touch target (IconButton would enlarge it to 48 dp and make the header taller).
@Composable
private fun HeaderButton(onClick: () -> Unit, icon: Int, label: Int) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
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
        is Step.Trim, is Step.Share -> stringResource(R.string.status_trim)
        is Step.Failed -> stringResource(R.string.status_failed)
        Step.Home -> ""
    }
    val progress = when (step) {
        is Step.Downloading -> step.progress
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
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
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
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
