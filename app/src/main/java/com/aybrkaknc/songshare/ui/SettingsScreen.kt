package com.aybrkaknc.songshare.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Session
import com.aybrkaknc.songshare.UpdateState
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.settings.SpotifyMethod
import com.aybrkaknc.songshare.ytdl.Ytdl

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var language by remember { mutableStateOf(prefs.language) }
    var method by remember { mutableStateOf(prefs.spotifyMethod) }
    var clientId by remember { mutableStateOf(prefs.spotifyClientId) }
    var clientSecret by remember { mutableStateOf(prefs.spotifyClientSecret) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var alwaysSave by remember { mutableStateOf(prefs.alwaysSave) }
    val update by Session.update.collectAsState()

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        TopRow(stringResource(R.string.settings), stringResource(R.string.back), onBack)

        Section(stringResource(R.string.language))
        listOf(
            "system" to R.string.language_system,
            "en" to R.string.language_en,
            "tr" to R.string.language_tr,
        ).forEach { (tag, label) ->
            Choice(stringResource(label), language == tag) {
                if (language != tag) {
                    language = tag
                    prefs.language = tag
                    // attachBaseContext applies the language, so the activity has to be rebuilt.
                    (context as? Activity)?.recreate()
                }
            }
        }

        Section(stringResource(R.string.spotify_method))
        listOf(
            SpotifyMethod.OPEN_GRAPH to R.string.method_open_graph,
            SpotifyMethod.WEB_API to R.string.method_web_api,
            SpotifyMethod.MANUAL to R.string.method_manual,
        ).forEach { (m, label) ->
            Choice(stringResource(label), method == m) {
                method = m
                prefs.spotifyMethod = m
            }
        }
        if (method == SpotifyMethod.WEB_API) {
            Text(stringResource(R.string.api_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it; prefs.spotifyClientId = it },
                label = { Text(stringResource(R.string.client_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = { clientSecret = it; prefs.spotifyClientSecret = it },
                label = { Text(stringResource(R.string.client_secret)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Section(stringResource(R.string.ytdlp))
        Toggle(stringResource(R.string.auto_update), autoUpdate) {
            autoUpdate = it
            prefs.autoUpdate = it
        }
        val version = remember(update) { Ytdl.version(context) }
        Text(
            stringResource(R.string.ytdlp_version, version ?: "-"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when (val u = update) {
                UpdateState.Idle -> ""
                UpdateState.Running -> stringResource(R.string.update_running)
                is UpdateState.Done -> stringResource(if (u.updated) R.string.update_done else R.string.update_current)
                is UpdateState.Failed -> stringResource(R.string.update_failed, u.detail ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { Session.updateNow(context) },
            enabled = update != UpdateState.Running,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(R.string.update_now)) }

        Section(stringResource(R.string.sharing))
        Toggle(stringResource(R.string.always_save), alwaysSave) {
            alwaysSave = it
            prefs.alwaysSave = it
        }
        Text(stringResource(R.string.save_location), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
