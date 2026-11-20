package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.aybrkaknc.songshare.audio.Audio

// Accepts m:ss.t; the new value is applied on Done and the field resets when it is invalid.
@Composable
internal fun TimeField(label: String, valueMs: Long, modifier: Modifier, onCommit: (Long?) -> Unit) {
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
