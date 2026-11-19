package com.aybrkaknc.songshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.ui.BusyScreen
import com.aybrkaknc.songshare.ui.FailedScreen
import com.aybrkaknc.songshare.ui.HomeScreen
import com.aybrkaknc.songshare.ui.SearchScreen
import com.aybrkaknc.songshare.ui.SettingsScreen
import com.aybrkaknc.songshare.ui.SongshareTheme
import com.aybrkaknc.songshare.ui.TrimScreen

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(withLanguage(newBase, Prefs(newBase).language))
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            handleShare(intent)
        }
        Session.autoUpdate(applicationContext)

        setContent {
            SongshareTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { Content() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // A new shared link replaces whatever job is on screen.
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Session.reset(applicationContext)
        Session.open(applicationContext, text)
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        val step by Session.step.collectAsState()
        var settings by rememberSaveable { mutableStateOf(false) }
        val ctx = applicationContext

        if (settings) {
            BackHandler { settings = false }
            SettingsScreen(onBack = { settings = false })
            return
        }
        BackHandler(enabled = step != Step.Home) {
            when (step) {
                is Step.Downloading -> Session.cancelDownload(ctx)
                else -> Session.reset(ctx)
            }
        }
        when (val s = step) {
            Step.Home -> HomeScreen(onOpen = { Session.open(ctx, it) }, onSettings = { settings = true })
            Step.Resolving -> BusyScreen(stringResource(R.string.resolving), onCancel = { Session.reset(ctx) })
            is Step.Search -> SearchScreen(
                step = s,
                onSearch = { Session.search(ctx, it) },
                onPick = { Session.pick(ctx, it) },
                onBack = { Session.reset(ctx) },
            )
            is Step.Downloading -> BusyScreen(
                stringResource(R.string.downloading),
                progress = s.progress,
                onCancel = { Session.cancelDownload(ctx) },
            )
            is Step.Trim -> key(s.file) { TrimScreen(s, onBack = { Session.reset(ctx) }) }
            is Step.Failed -> FailedScreen(s, onBack = { Session.reset(ctx) })
        }
    }
}

// "system" keeps the device language; otherwise the context is forced to the chosen locale.
internal fun withLanguage(base: Context, lang: String): Context {
    if (lang == "system") return base
    val config = android.content.res.Configuration(base.resources.configuration)
    config.setLocale(java.util.Locale.forLanguageTag(lang))
    return base.createConfigurationContext(config)
}
