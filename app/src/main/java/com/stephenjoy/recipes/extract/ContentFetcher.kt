package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.SourcePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.util.concurrent.TimeUnit

/** Everything we could learn about a shared post before asking the AI to structure it. */
data class SourceContent(
    val url: String,
    val platform: SourcePlatform,
    val title: String = "",
    val author: String = "",
    /** Video description / post caption / article text. */
    val text: String = "",
    /** Spoken words from captions, when the platform exposes them. */
    val transcript: String = "",
    val imageUrl: String = "",
    /** A complete recipe found in schema.org markup, if the page had one. */
    val structuredRecipe: Recipe? = null,
) {
    val hasText: Boolean get() = (text + transcript).isNotBlank()
}

class ContentFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(rawUrl: String): SourceContent = withContext(Dispatchers.IO) {
        val url = resolveShortLink(rawUrl)
        when (val platform = LinkResolver.platformOf(url)) {
            SourcePlatform.YOUTUBE, SourcePlatform.YOUTUBE_SHORTS -> fetchYouTube(url, platform)
            SourcePlatform.INSTAGRAM -> fetchInstagram(url)
            SourcePlatform.TIKTOK -> fetchTikTok(url)
            else -> fetchWebPage(url, platform)
        }
    }

    // ---------------------------------------------------------------- YouTube

    private fun fetchYouTube(url: String, platform: SourcePlatform): SourceContent {
        val id = LinkResolver.youtubeVideoId(url) ?: return fetchWebPage(url, platform)
        val watchUrl = LinkResolver.youtubeWatchUrl(id)
        var title = ""
        var author = ""
        var description = ""
        var transcript = ""
        runCatching {
            // SOCS cookie skips the EU consent interstitial.
            val html = get(watchUrl, cookie = "SOCS=CAI; CONSENT=YES+1")
            val player = extractJsonAssignment(html, "ytInitialPlayerResponse")?.jsonObject
            val details = player?.get("videoDetails") as? JsonObject
            title = details?.str("title").orEmpty()
            author = details?.str("author").orEmpty()
            description = details?.str("shortDescription").orEmpty()
            transcript = player?.let { fetchYouTubeCaptions(it) }.orEmpty()
        }
        if (title.isBlank()) {
            runCatching {
                val o = json.parseToJsonElement(get("https://www.youtube.com/oembed?format=json&url=$watchUrl")).jsonObject
                title = o.str("title")
                author = o.str("author_name")
            }
        }
        return SourceContent(
            url = url,
            platform = platform,
            title = title,
            author = author,
            text = description,
            transcript = transcript,
            imageUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }

    private fun fetchYouTubeCaptions(player: JsonObject): String {
        val tracks = ((player["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray ?: return ""
        val objects = tracks.mapNotNull { it as? JsonObject }
        // Prefer English, then manual over auto-generated ("asr"), then whatever exists.
        val track = objects.firstOrNull { it.str("languageCode").startsWith("en") && it.str("kind") != "asr" }
            ?: objects.firstOrNull { it.str("languageCode").startsWith("en") }
            ?: objects.firstOrNull()
            ?: return ""
        val baseUrl = track.str("baseUrl").ifBlank { return "" }
        val xml = runCatching { get(baseUrl) }.getOrNull() ?: return ""
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("text, p").joinToString(" ") { Parser.unescapeEntities(it.text(), false) }
            .replace(Regex("""\s+"""), " ").trim()
    }

    // -------------------------------------------------------------- Instagram

    private fun fetchInstagram(url: String): SourceContent {
        val code = LinkResolver.instagramShortcode(url)
        var caption = ""
        var author = ""
        var image = ""
        if (code != null) {
            // The public embed page carries the full caption without requiring a login.
            runCatching {
                val doc = Jsoup.parse(get("https://www.instagram.com/p/$code/embed/captioned/"))
                val captionEl = doc.selectFirst(".Caption")
                captionEl?.select(".CaptionUsername, .CaptionComments")?.remove()
                caption = captionEl?.wholeText()?.trim().orEmpty()
                author = doc.selectFirst(".UsernameText, .Username")?.text().orEmpty()
                image = doc.selectFirst("img.EmbeddedMediaImage")?.attr("src").orEmpty()
            }
        }
        // Open Graph tags (served to link-preview crawlers) as a fallback / for the thumbnail.
        val og = runCatching { Jsoup.parse(get(url, userAgent = PREVIEW_UA)) }.getOrNull()
        val ogDescription = og?.meta("og:description").orEmpty()
        val ogTitle = og?.meta("og:title").orEmpty()
        if (caption.isBlank()) {
            // og:description looks like: "1,234 likes, 56 comments - chef on May 1, 2024: "caption...""
            caption = Regex("""^.*?:\s*["“](.*)["”]\s*\.?$""", RegexOption.DOT_MATCHES_ALL)
                .find(ogDescription)?.groupValues?.get(1) ?: ogDescription
        }
        if (author.isBlank()) author = Regex("""^(.*?) on Instagram""").find(ogTitle)?.groupValues?.get(1).orEmpty()
        if (image.isBlank()) image = og?.meta("og:image").orEmpty()
        return SourceContent(
            url = url,
            platform = SourcePlatform.INSTAGRAM,
            title = "",
            author = author,
            text = caption,
            imageUrl = image,
        )
    }

    // ----------------------------------------------------------------- TikTok

    private fun fetchTikTok(url: String): SourceContent {
        val o = runCatching { json.parseToJsonElement(get("https://www.tiktok.com/oembed?url=$url")).jsonObject }.getOrNull()
        if (o == null) return fetchWebPage(url, SourcePlatform.TIKTOK)
        return SourceContent(
            url = url,
            platform = SourcePlatform.TIKTOK,
            author = o.str("author_name"),
            text = o.str("title"),
            imageUrl = o.str("thumbnail_url"),
        )
    }

    // -------------------------------------------------------------- Any page

    private fun fetchWebPage(url: String, platform: SourcePlatform): SourceContent {
        val html = get(url, userAgent = if (platform == SourcePlatform.WEB) BROWSER_UA else PREVIEW_UA)
        val doc = Jsoup.parse(html, url)
        val jsonLd = doc.select("script[type=application/ld+json]").map { it.data() }
        val structured = JsonLdRecipeParser.parse(jsonLd)
        doc.select("script, style, nav, header, footer, aside, noscript, form, iframe").remove()
        val main = doc.selectFirst("article, main, [role=main], .recipe, .wprm-recipe-container") ?: doc.body()
        val text = main?.wholeText()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n").orEmpty()
        return SourceContent(
            url = url,
            platform = platform,
            title = doc.meta("og:title").ifBlank { doc.title() },
            author = doc.meta("author").ifBlank { doc.meta("og:site_name") },
            text = listOf(doc.meta("og:description"), text).filter { it.isNotBlank() }.joinToString("\n\n"),
            imageUrl = doc.meta("og:image").ifBlank { structured?.imageUrl.orEmpty() },
            structuredRecipe = structured,
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Follows redirects of short links (youtu.be is handled by id parsing, pin.it / fb.watch here). */
    private fun resolveShortLink(url: String): String {
        val host = LinkResolver.hostOf(url)
        if (host !in setOf("pin.it", "fb.watch", "vm.tiktok.com", "vt.tiktok.com", "instagr.am")) return url
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).head().build())
                .execute().use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    private fun get(url: String, userAgent: String = BROWSER_UA, cookie: String? = null): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    /** Finds `name = {...};` in a page script and parses the balanced JSON object. */
    private fun extractJsonAssignment(html: String, name: String): JsonElement? {
        val start = Regex("""$name\s*=\s*\{""").find(html)?.let { it.range.last } ?: return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runCatching { json.parseToJsonElement(html.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun Document.meta(name: String): String =
        selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")?.attr("content").orEmpty()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        private const val PREVIEW_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }
}
