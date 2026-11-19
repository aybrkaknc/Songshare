package com.aybrkaknc.songshare.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class SpotifyMethod { OPEN_GRAPH, WEB_API, MANUAL }

class Prefs(context: Context) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Spotify credentials stay encrypted on the device; they are never part of the source.
    private val secrets: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // "system", "en" or "tr". Applied in MainActivity.attachBaseContext.
    var language: String
        get() = settings.getString(KEY_LANGUAGE, "system") ?: "system"
        set(v) = settings.edit { putString(KEY_LANGUAGE, v) }

    var spotifyMethod: SpotifyMethod
        get() = runCatching { SpotifyMethod.valueOf(settings.getString(KEY_SPOTIFY_METHOD, null) ?: "") }
            .getOrDefault(SpotifyMethod.OPEN_GRAPH)
        set(v) = settings.edit { putString(KEY_SPOTIFY_METHOD, v.name) }

    var autoUpdate: Boolean
        get() = settings.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = settings.edit { putBoolean(KEY_AUTO_UPDATE, v) }

    // When on, the trimmed clip is copied to Download/Songshare after sharing without asking.
    var alwaysSave: Boolean
        get() = settings.getBoolean(KEY_ALWAYS_SAVE, false)
        set(v) = settings.edit { putBoolean(KEY_ALWAYS_SAVE, v) }

    var spotifyClientId: String
        get() = secrets.getString(KEY_CLIENT_ID, "") ?: ""
        set(v) = secrets.edit { putString(KEY_CLIENT_ID, v.trim()) }

    var spotifyClientSecret: String
        get() = secrets.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(v) = secrets.edit { putString(KEY_CLIENT_SECRET, v.trim()) }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_SPOTIFY_METHOD = "spotify_method"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_ALWAYS_SAVE = "always_save"
        const val KEY_CLIENT_ID = "spotify_client_id"
        const val KEY_CLIENT_SECRET = "spotify_client_secret"
    }
}
