package com.aybrkaknc.songshare

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Opened from the launcher icon: the same steps as the share panel, on a full page.
class MainActivity : PanelActivity() {
    @Composable
    override fun Root() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) { PanelContent(close = {}) }
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
