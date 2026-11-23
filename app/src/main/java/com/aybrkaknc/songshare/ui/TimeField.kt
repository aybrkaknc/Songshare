package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.audio.Audio

// Edits a time as m:ss.t. The value is applied with Done; an invalid entry keeps the dialog open.
@Composable
internal fun TimeEditDialog(label: String, valueMs: Long, onCommit: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Audio.formatMs(valueMs)) }
    val parsed = Audio.parseMs(text)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onCommit) }, enabled = parsed != null) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
