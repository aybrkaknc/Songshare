package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppColors {
    val Bg = Color(0xFF121212)
    val Surface = Color(0xFF1E1E1E)
    val Accent = Color(0xFF25D366)
    val Wave = Color(0xFF5A5A5A)
}

@Composable
fun SongshareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = Color.Black,
            background = AppColors.Bg,
            surface = AppColors.Bg,
            surfaceContainer = AppColors.Surface,
            surfaceContainerHigh = AppColors.Surface,
        ),
        content = content,
    )
}

// Screen title row with an optional action on the right.
@Composable
fun TopRow(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
