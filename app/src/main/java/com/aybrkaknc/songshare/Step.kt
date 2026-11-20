package com.aybrkaknc.songshare

import androidx.annotation.StringRes
import com.aybrkaknc.songshare.source.TrackInfo
import com.aybrkaknc.songshare.ytdl.Candidate
import java.io.File

// The screen the app is on; Session moves between these and MainActivity draws the matching one.
sealed interface Step {
    data object Home : Step
    // info is what the source link gave; query starts from it and the user may edit it.
    data class Search(
        val info: TrackInfo?,
        val query: String,
        val candidates: List<Candidate> = emptyList(),
        val loading: Boolean = false,
        @param:StringRes val error: Int? = null,
    ) : Step

    // picked is the search result the user tapped; null when the link itself is downloaded.
    // converting is true once yt-dlp starts turning the file into MP3, after the download itself.
    data class Downloading(val progress: Float, val picked: Candidate? = null, val converting: Boolean = false) : Step
    data class Trim(val file: File, val name: String, val durationMs: Long) : Step
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : Step
}
