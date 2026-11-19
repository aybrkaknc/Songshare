package com.aybrkaknc.songshare.source

import java.net.URI

enum class Source { YOUTUBE, SPOTIFY, DEEZER, APPLE_MUSIC, SOUNDCLOUD, BANDCAMP, UNSUPPORTED }

// A shared link plus the text around it (e.g. "Listen to X by Y on Deezer"), used as a search fallback.
data class SharedLink(val url: String, val source: Source, val extraText: String)

object Link {
    private val urlRegex = Regex("""https?://\S+""")

    fun parse(text: String?): SharedLink? {
        if (text.isNullOrBlank()) return null
        val url = urlRegex.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'') ?: return null
        val extra = text.replace(url, " ").replace(Regex("\\s+"), " ").trim()
        return SharedLink(url, sourceOf(url), extra)
    }

    fun sourceOf(url: String): Source {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return Source.UNSUPPORTED
        fun matches(vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }
        return when {
            matches("youtube.com", "youtu.be") -> Source.YOUTUBE
            matches("spotify.com", "spotify.link", "spotify.app.link") -> Source.SPOTIFY
            matches("deezer.com", "deezer.page.link") -> Source.DEEZER
            matches("music.apple.com", "itunes.apple.com") -> Source.APPLE_MUSIC
            matches("soundcloud.com") -> Source.SOUNDCLOUD
            matches("bandcamp.com") -> Source.BANDCAMP
            else -> Source.UNSUPPORTED
        }
    }
}
