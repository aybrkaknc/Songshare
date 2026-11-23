package com.aybrkaknc.songshare

import androidx.annotation.StringRes
import com.aybrkaknc.songshare.share.ClipDraft
import com.aybrkaknc.songshare.share.ShareKind
import com.aybrkaknc.songshare.source.TrackInfo
import com.aybrkaknc.songshare.ytdl.Candidate
import java.io.File

// Results per search page; "show more" asks for another page, up to the maximum.
const val SEARCH_PAGE = 5
const val SEARCH_MAX = 20

// The screen the app is on; Session moves between these and the panel draws the matching one.
sealed interface Step {
    data object Home : Step
    // info is what the source link gave; query starts from it and the user may edit it.
    // limit is how many results the last search asked for.
    data class Search(
        val info: TrackInfo?,
        val query: String,
        val candidates: List<Candidate> = emptyList(),
        val loading: Boolean = false,
        val limit: Int = SEARCH_PAGE,
        // Shown when nothing came back: YouTube's best guess at what the user meant.
        val suggestion: String? = null,
        @param:StringRes val error: Int? = null,
    ) : Step

    // picked is the search result the user tapped; null when the link itself is downloaded.
    // converting is true once yt-dlp starts turning the file into MP3, after the download itself.
    data class Downloading(val progress: Float, val picked: Candidate? = null, val converting: Boolean = false) : Step
    // artist and title drive the lyrics lookup; thumb is the cover for the lyrics card (empty for a direct link).
    data class Trim(
        val file: File,
        val name: String,
        val durationMs: Long,
        val artist: String = "",
        val title: String = "",
        val thumb: String? = null,
    ) : Step
    // The share page: the saved clip files and the edits they came from; trim is where Back returns to.
    // initial is the format picked on the trim page; the share page starts with it selected.
    data class Share(val trim: Trim, val draft: ClipDraft, val files: Map<ShareKind, File>, val initial: ShareKind = ShareKind.MP3) : Step
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : Step
}
