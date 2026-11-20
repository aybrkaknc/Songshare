package com.aybrkaknc.songshare

import android.content.Context
import com.aybrkaknc.songshare.settings.Prefs
import com.aybrkaknc.songshare.ytdl.Ytdl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    private var autoChecked = false

    fun autoUpdate(context: Context) {
        if (autoChecked || !Prefs(context).autoUpdate) return
        autoChecked = true
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
