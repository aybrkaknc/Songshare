package com.aybrkaknc.songshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.ui.ActiveCard
import com.aybrkaknc.songshare.ui.DownloadScreen
import com.aybrkaknc.songshare.ui.FailedScreen
import com.aybrkaknc.songshare.ui.HomeScreen
import com.aybrkaknc.songshare.ui.LogScreen
import com.aybrkaknc.songshare.ui.PanelHeader
import com.aybrkaknc.songshare.ui.PanelSheetState
import com.aybrkaknc.songshare.ui.PermissionCard
import com.aybrkaknc.songshare.ui.SearchScreen
import com.aybrkaknc.songshare.ui.SettingsScreen
import com.aybrkaknc.songshare.ui.SongshareTheme
import com.aybrkaknc.songshare.ui.TrimScreen

private enum class Page { MAIN, SETTINGS, LOG }

// Height of the drag handle in dp; the header moves up by this much where the handle is hidden.
private const val HANDLE_DP = 22f

// Shared by the full-page app (launcher icon) and the bottom panel (share menu): both show the same queue and steps.
abstract class PanelActivity : ComponentActivity() {
    private val notificationsOn = mutableStateOf(false)

    // Links shared before notifications were allowed; they start once the permission is granted.
    private val pending = mutableListOf<String>()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermission() }

    // Where the steps are drawn: a plain page or a bottom sheet.
    @Composable
    protected abstract fun Root()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(withLanguage(newBase, Prefs(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Session.attach(this)
        refreshPermission()
        if (savedInstanceState == null) handleShare(intent)
        Updater.autoUpdate(applicationContext)
        // The save folders are checked on every start, so ones deleted by hand come back.
        val saveDir = Prefs(this).saveDir
        if (saveDir.isBlank()) Audio.ensureDefaultFolders() else Audio.ensureTreeFolders(this, saveDir)

        setContent { SongshareTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
        Session.setVisible(true)
        refreshPermission()
    }

    override fun onStop() {
        Session.setVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        submit(intent.getStringExtra(Intent.EXTRA_TEXT) ?: return)
    }

    private fun submit(text: String) {
        if (notificationsOn.value) Session.submit(this, text) else pending += text
    }

    private fun refreshPermission() {
        notificationsOn.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (notificationsOn.value && pending.isNotEmpty()) {
            pending.forEach { Session.submit(this, it) }
            pending.clear()
        }
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openNotificationSettings()
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    // Emits the header and the current step one under another, so call it inside a Column.
    @Composable
    protected fun ColumnScope.PanelContent(close: () -> Unit, sheet: PanelSheetState? = null) {
        val step by Session.step.collectAsState()
        val active by Session.active.collectAsState()
        val replacement by Session.pendingReplace.collectAsState()
        val allowed by notificationsOn
        var page by rememberSaveable { mutableStateOf(Page.MAIN) }
        val ctx = this@PanelActivity

        // Back goes up one settings level before it closes anything.
        // Back from settings puts the page's own height back: search wraps its content, the others keep their fixed size.
        fun leaveSettings() {
            val s = sheet ?: return
            s.unlock()
            val fixed = heightFor(step)
            if (fixed == null) s.useAuto() else s.fitTo(fixed)
        }

        BackHandler(enabled = page != Page.MAIN) {
            if (page == Page.LOG) {
                page = Page.SETTINGS
            } else {
                page = Page.MAIN
                leaveSettings()
            }
        }
        LaunchedEffect(page, step) {
            sheet?.listHandoff = page == Page.MAIN
            sheet?.dragEnabled = page == Page.MAIN && step is Step.Search
        }

        // A link shared while a song is in progress: the user chooses which one goes on.
        if (replacement != null) {
            AlertDialog(
                onDismissRequest = { Session.keepCurrent() },
                title = { Text(stringResource(R.string.replace_title)) },
                text = { Text(stringResource(R.string.replace_text)) },
                confirmButton = {
                    TextButton(onClick = { Session.confirmReplace(ctx) }) { Text(stringResource(R.string.replace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { Session.keepCurrent() }) { Text(stringResource(R.string.replace_keep)) }
                },
            )
        }

        // Where the handle is hidden, the header moves up into its space; the content below stays where it was.
        val lift = if (sheet != null && !sheet.dragEnabled) -HANDLE_DP.dp else 0.dp

        when (page) {
            Page.SETTINGS -> {
                Box(Modifier.fillMaxWidth().offset(y = lift)) {
                    PanelHeader(stringResource(R.string.settings), onBack = {
                        page = Page.MAIN
                        leaveSettings()
                    })
                }
                SettingsScreen(onOpenLog = { page = Page.LOG })
                return
            }
            Page.LOG -> {
                Box(Modifier.fillMaxWidth().offset(y = lift)) {
                    PanelHeader(stringResource(R.string.log), onBack = { page = Page.SETTINGS })
                }
                LogScreen()
                return
            }
            Page.MAIN -> Unit
        }

        val title = when (step) {
            is Step.Search -> R.string.search
            is Step.Downloading -> R.string.downloading
            is Step.Trim -> R.string.trim_title
            else -> R.string.app_name
        }
        Box(Modifier.fillMaxWidth().offset(y = lift)) {
            PanelHeader(
                stringResource(title),
                // Every page except home has a cancel that goes back to home.
                onCancel = if (step !is Step.Home) ({ Session.finish(ctx) }) else null,
                // The save folder is one tap away on every page.
                onFolder = { Audio.openFolder(ctx, Prefs(ctx).saveDir) },
                onSettings = {
                    // Settings are long, so the panel opens fully.
                    page = Page.SETTINGS
                    sheet?.openSettings()
                },
            )
        }
        if (!allowed) {
            PermissionCard(onAllow = ::askPermission, onSettings = ::openNotificationSettings)
            return
        }
        // Only the failure page keeps the song card; search, download and trim have their own layouts.
        if (step is Step.Failed) active?.let { ActiveCard(step, it) { Session.finish(ctx) } }

        // Moving between search and download slides the page; updates within a page do not animate.
        AnimatedContent(
            targetState = step,
            // A page takes only the room it needs, so the search page can wrap its content.
            modifier = Modifier.weight(1f, fill = false),
            // Pages start at the top; the default centering pushed a taller page up into the header.
            contentAlignment = Alignment.TopStart,
            contentKey = { it.javaClass },
            transitionSpec = {
                if (targetState is Step.Downloading) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "panel page",
        ) { s ->
            Column(Modifier.fillMaxWidth()) {
                when (s) {
                    Step.Home -> HomeScreen(onOpen = { submit(it) })
                    is Step.Downloading -> DownloadScreen(s, active)
                    is Step.Search -> SearchScreen(
                        s,
                        onSearch = { Session.search(ctx, it) },
                        onPick = { Session.pick(ctx, it) },
                        onClear = { Session.finish(ctx) },
                    )
                    is Step.Trim -> key(s.file) {
                        TrimScreen(s, onDone = {
                            Session.finish(ctx)
                            if (Session.active.value == null) close()
                        })
                    }
                    is Step.Failed -> FailedScreen(
                        s,
                        onRetry = { Session.retry(ctx) },
                        onManual = { Session.typeManually() },
                        onSkip = { Session.finish(ctx) },
                    )
                }
            }
        }
    }
}
