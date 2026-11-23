package com.aybrkaknc.songshare.ui

import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

// How dark the screen behind a dialog gets: 75% black.
private const val DIM_AMOUNT = 0.75f

// AlertDialog with the app's fixed dim behind it, so every dialog darkens the page the same way.
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = {
            // The dialog window is the parent of its content; its dim is set here once the content is attached.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(DIM_AMOUNT)
            }
            text?.invoke()
        },
    )
}
