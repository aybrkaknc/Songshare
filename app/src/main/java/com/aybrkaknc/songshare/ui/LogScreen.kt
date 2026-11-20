package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.AppLog
import com.aybrkaknc.songshare.R

@Composable
fun LogScreen() {
    val lines by AppLog.lines.collectAsState()
    val clipboard = LocalClipboardManager.current
    val text = if (lines.isEmpty()) stringResource(R.string.log_empty) else lines.joinToString("\n")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Text(stringResource(R.string.log_copy))
            }
            OutlinedButton(onClick = { AppLog.clear() }) {
                Text(stringResource(R.string.log_clear))
            }
        }
    }
}
