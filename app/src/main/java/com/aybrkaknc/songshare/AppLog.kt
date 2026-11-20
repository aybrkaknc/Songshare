package com.aybrkaknc.songshare

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// In-memory debug log shown in Settings. Cleared when the process ends.
object AppLog {
    private const val TAG = "Songshare"
    private const val MAX_LINES = 200
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val state = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = state

    fun d(message: String) {
        Log.d(TAG, message)
        val line = "${LocalTime.now().format(time)} $message"
        synchronized(this) { state.value = (state.value + line).takeLast(MAX_LINES) }
    }

    fun clear() = synchronized(this) { state.value = emptyList() }
}
