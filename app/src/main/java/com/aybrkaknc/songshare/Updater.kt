package com.aybrkaknc.songshare

import android.content.Context
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val DAY_MS = 24L * 60 * 60 * 1000

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Running : UpdateState
    data class Done(val updated: Boolean) : UpdateState
    data class Failed(val detail: String?) : UpdateState
}

// yt-dlp update state, shared by the launch check and the Settings button.
object Updater {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The launch check runs at most once a day; the time of the last check is kept in the settings.
    fun autoUpdate(context: Context) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (!prefs.autoUpdate) return
        if (now - prefs.lastUpdateCheckMs < DAY_MS) {
            AppLog.d("update check: skipped, checked within the last day")
            return
        }
        prefs.lastUpdateCheckMs = now
        updateNow(context)
    }

    fun updateNow(context: Context) {
        if (state.value == UpdateState.Running) return
        state.value = UpdateState.Running
        scope.launch {
            state.value = runCatching { Ytdl.update(context.applicationContext) }
                .fold({ UpdateState.Done(it) }, { AppLog.d("update failed: ${it.message}"); UpdateState.Failed(it.message) })
        }
    }
}
