package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.domain.SourcePlatform
import java.net.URI

object LinkResolver {

    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Shared text is often "Check this out! https://..." — pull out the first link. */
    fun extractUrl(sharedText: String?): String? =
        sharedText?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?') }

    fun platformOf(url: String): SourcePlatform {
        val host = hostOf(url)
        return when {
            host.endsWith("youtube.com") || host == "youtu.be" ->
                if (url.contains("/shorts/")) SourcePlatform.YOUTUBE_SHORTS else SourcePlatform.YOUTUBE
            host.endsWith("instagram.com") || host == "instagr.am" -> SourcePlatform.INSTAGRAM
            host.endsWith("tiktok.com") -> SourcePlatform.TIKTOK
            host.endsWith("facebook.com") || host == "fb.watch" -> SourcePlatform.FACEBOOK
            host.endsWith("pinterest.com") || host == "pin.it" || host.contains("pinterest.") -> SourcePlatform.PINTEREST
            else -> SourcePlatform.WEB
        }
    }

    fun youtubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
            Regex("""/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{6,})"""),
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    /** Canonical watch URL — both Shorts and regular videos share the same watch page. */
    fun youtubeWatchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun instagramShortcode(url: String): String? =
        Regex("""instagram\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

    fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }.getOrNull() ?: ""
}
