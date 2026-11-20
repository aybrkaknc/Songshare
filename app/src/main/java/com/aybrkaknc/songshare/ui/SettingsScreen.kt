package com.aybrkaknc.songshare.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import com.aybrkaknc.songshare.audio.Audio
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.aybrkaknc.songshare.UpdateState
import com.aybrkaknc.songshare.Updater
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.ytdl.Ytdl

private val LANGUAGES = listOf(
    "system" to R.string.language_system,
    "en" to R.string.language_en,
    "tr" to R.string.language_tr,
)

// Laid out like the Android system Settings: category headers over rows with a title and a summary.
@Composable
fun SettingsScreen(onOpenLog: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var language by remember { mutableStateOf(prefs.language) }
    var clientId by remember { mutableStateOf(prefs.spotifyClientId) }
    var clientSecret by remember { mutableStateOf(prefs.spotifyClientSecret) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var saveDir by remember { mutableStateOf(prefs.saveDir) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keep the permission across restarts so clips can still be written later.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            saveDir = uri.toString()
            prefs.saveDir = saveDir
            Audio.ensureTreeFolders(context, saveDir)
        }
    }
    var hideLong by remember { mutableStateOf(prefs.hideLongResults) }
    var languageDialog by remember { mutableStateOf(false) }
    var apiDialog by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsState()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Category(stringResource(R.string.general))
        Pref(
            stringResource(R.string.language),
            stringResource(LANGUAGES.firstOrNull { it.first == language }?.second ?: R.string.language_system),
        ) { languageDialog = true }
        SwitchPref(stringResource(R.string.hide_long), stringResource(R.string.hide_long_summary), hideLong) {
            hideLong = it
            prefs.hideLongResults = it
        }

        Category(stringResource(R.string.spotify))
        Pref(
            stringResource(R.string.spotify_api),
            stringResource(
                if (clientId.isNotBlank() && clientSecret.isNotBlank()) R.string.api_configured else R.string.api_not_configured,
            ),
        ) { apiDialog = true }

        Category(stringResource(R.string.ytdlp))
        SwitchPref(stringResource(R.string.auto_update), null, autoUpdate) {
            autoUpdate = it
            prefs.autoUpdate = it
        }
        val version = remember(update) { Ytdl.version(context) }
        Pref(
            stringResource(R.string.update_now),
            when (val u = update) {
                UpdateState.Idle -> stringResource(R.string.ytdlp_version, version ?: "-")
                UpdateState.Running -> stringResource(R.string.update_running)
                is UpdateState.Done -> stringResource(if (u.updated) R.string.update_done else R.string.update_current)
                is UpdateState.Failed -> stringResource(R.string.update_failed, u.detail ?: "")
            },
            enabled = update != UpdateState.Running,
        ) { Updater.updateNow(context) }

        Category(stringResource(R.string.sharing))
        Pref(
            stringResource(R.string.save_dir),
            if (saveDir.isBlank()) stringResource(R.string.save_dir_default) else Uri.parse(saveDir).lastPathSegment ?: saveDir,
        ) { folderPicker.launch(null) }
        if (saveDir.isNotBlank()) {
            Pref(stringResource(R.string.save_dir_reset), null) {
                saveDir = ""
                prefs.saveDir = ""
            }
        }

        Category(stringResource(R.string.troubleshooting))
        Pref(stringResource(R.string.log), null, onClick = onOpenLog)
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    LANGUAGES.forEach { (tag, label) ->
                        Choice(stringResource(label), language == tag) {
                            languageDialog = false
                            if (language != tag) {
                                language = tag
                                prefs.language = tag
                                // attachBaseContext applies the language, so the activity has to be rebuilt.
                                (context as? Activity)?.recreate()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (apiDialog) {
        var id by remember { mutableStateOf(clientId) }
        var secret by remember { mutableStateOf(clientSecret) }
        AlertDialog(
            onDismissRequest = { apiDialog = false },
            title = { Text(stringResource(R.string.spotify_api)) },
            text = {
                Column {
                    Text(stringResource(R.string.api_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text(stringResource(R.string.client_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.client_secret)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.spotifyClientId = id
                    prefs.spotifyClientSecret = secret
                    clientId = prefs.spotifyClientId
                    clientSecret = prefs.spotifyClientSecret
                    apiDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { apiDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Category(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pref(title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (summary.isNullOrBlank()) null else { { Text(summary) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchPref(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (summary.isNullOrBlank()) null else { { Text(summary) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
