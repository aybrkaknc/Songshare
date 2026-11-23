package com.aybrkaknc.songshare.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    // "system", "en" or "tr". Applied in PanelActivity.attachBaseContext.
    var language: String
        get() = settings.getString(KEY_LANGUAGE, "system") ?: "system"
        set(v) = settings.edit { putString(KEY_LANGUAGE, v) }

    var autoUpdate: Boolean
        get() = settings.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = settings.edit { putBoolean(KEY_AUTO_UPDATE, v) }

    // Folder the clips are saved to, as a tree URI picked by the user. Empty means Music/Clipped Songs.
    var saveDir: String
        get() = settings.getString(KEY_SAVE_DIR, "") ?: ""
        set(v) = settings.edit { putString(KEY_SAVE_DIR, v) }


    // When on, search results longer than ten minutes (albums, mixes) are left out.
    var hideLongResults: Boolean
        get() = settings.getBoolean(KEY_HIDE_LONG, true)
        set(v) = settings.edit { putBoolean(KEY_HIDE_LONG, v) }

    // When yt-dlp was last checked for an update at launch (epoch milliseconds); the check runs at most once a day.
    var lastUpdateCheckMs: Long
        get() = settings.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = settings.edit { putLong(KEY_LAST_UPDATE_CHECK, v) }

    // Set after the first lyric line was aligned by a double tap; the hint effect on the first line stops then.
    var syncHintDone: Boolean
        get() = settings.getBoolean(KEY_SYNC_HINT_DONE, false)
        set(v) = settings.edit { putBoolean(KEY_SYNC_HINT_DONE, v) }

    // The last downloaded songs as a JSON array; Session reads and writes it.
    var recentsJson: String
        get() = settings.getString(KEY_RECENTS, "[]") ?: "[]"
        set(v) = settings.edit { putString(KEY_RECENTS, v) }

    var spotifyClientId: String
        get() = secrets.getString(KEY_CLIENT_ID, "") ?: ""
        set(v) = secrets.edit { putString(KEY_CLIENT_ID, v.trim()) }

    var spotifyClientSecret: String
        get() = secrets.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(v) = secrets.edit { putString(KEY_CLIENT_SECRET, v.trim()) }

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_HIDE_LONG = "hide_long_results"
        const val KEY_SAVE_DIR = "save_dir"
        const val KEY_RECENTS = "recents"
        const val KEY_SYNC_HINT_DONE = "sync_hint_done"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_CLIENT_ID = "spotify_client_id"
        const val KEY_CLIENT_SECRET = "spotify_client_secret"
    }
}
