package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.ytdl.Candidate

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TopRow(stringResource(R.string.app_name), stringResource(R.string.settings), onSettings)
        Text(stringResource(R.string.home_hint), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.paste_link)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onOpen(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onOpen(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open))
        }
    }
}

@Composable
fun BusyScreen(message: String, progress: Float? = null, onCancel: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        if (progress == null || progress < 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${progress.toInt()}%", Modifier.padding(top = 8.dp))
        }
        if (onCancel != null) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
fun FailedScreen(step: Step.Failed, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(step.message), style = MaterialTheme.typography.titleMedium)
        if (!step.detail.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(step.detail, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

// Candidate list for links that cannot be downloaded directly; the query stays editable.
@Composable
fun SearchScreen(
    step: Step.Search,
    onSearch: (String) -> Unit,
    onPick: (Candidate) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable(step.query) { mutableStateOf(step.query) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TopRow(stringResource(R.string.search_title), stringResource(R.string.back), onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_query)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSearch(query) },
            enabled = query.isNotBlank() && !step.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search)) }
        Spacer(Modifier.height(12.dp))
        step.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (step.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
        } else {
            if (step.candidates.isNotEmpty()) {
                Text(stringResource(R.string.pick_candidate), style = MaterialTheme.typography.labelLarge)
            }
            LazyColumn {
                items(step.candidates) { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp),
                    ) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row {
                            Text(
                                c.channel,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (c.durationSec > 0) {
                                Text(Audio.formatMs(c.durationSec * 1000L).substringBefore('.'), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
