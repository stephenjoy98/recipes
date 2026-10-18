package com.stephenjoy.recipes.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class PostComment(val author: String, val text: String)

/**
 * Best-effort readers for the public (logged-out) Instagram pages. Instagram changes its markup
 * often and hides most data from logged-out visitors, so every reader here tolerates missing data.
 * Pure Kotlin so it can be unit-tested against saved HTML.
 */
object InstagramPage {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Direct .mp4 URL of the reel/video, from embedded JSON, JSON-LD or Open Graph tags. */
    fun videoUrl(html: String): String? {
        val patterns = listOf(
            Regex(""""video_url"\s*:\s*"([^"]+)""""),
            Regex(""""contentUrl"\s*:\s*"([^"]+?\.mp4[^"]*)""""),
            Regex("""<meta[^>]+property="og:video(?::secure_url)?"[^>]+content="([^"]+)""""),
            Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:video(?::secure_url)?""""),
            Regex("""<video[^>]+src="([^"]+)""""),
        )
        return patterns.firstNotNullOfOrNull { p -> p.find(html)?.groupValues?.get(1) }
            ?.let(::unescape)
            ?.takeIf { it.startsWith("http") }
    }

    /**
     * Comments visible in the page data. The creator's own comments come first, since recipes are
     * often posted there ("full recipe in the comments").
     */
    fun comments(html: String, owner: String, limit: Int = 30): List<PostComment> {
        val found = mutableListOf<PostComment>()
        jsonBlobs(html).forEach { blob ->
            val root = runCatching { json.parseToJsonElement(blob) }.getOrNull() ?: return@forEach
            collect(root, inCommentContext = false, out = found)
        }
        val ownerKey = owner.trim().removePrefix("@").lowercase()
        return found
            .filter { it.text.isNotBlank() }
            .distinctBy { it.author.lowercase() to it.text.trim() }
            .sortedByDescending { ownerKey.isNotEmpty() && it.author.lowercase() == ownerKey }
            .take(limit)
    }

    /** JSON from <script type="application/json|ld+json"> tags and inline data assignments. */
    private fun jsonBlobs(html: String): List<String> {
        val scripts = Regex("""<script[^>]*type="application/(?:ld\+)?json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1].trim() }.toMutableList()
        // Older pages: window._sharedData = {...}; and similar.
        Regex("""(?:_sharedData|__additionalData\w*)\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { scripts += it.groupValues[1] }
        return scripts.filter { it.startsWith("{") || it.startsWith("[") }
    }

    private fun collect(e: JsonElement, inCommentContext: Boolean, out: MutableList<PostComment>) {
        when (e) {
            is JsonArray -> e.forEach { collect(it, inCommentContext, out) }
            is JsonObject -> {
                val type = (e["@type"] as? JsonPrimitive)?.contentOrNull
                val text = (e["text"] as? JsonPrimitive)?.contentOrNull
                val author = authorOf(e)
                if (text != null && author != null && (inCommentContext || type.equals("Comment", true))) {
                    out += PostComment(author, text)
                }
                e.forEach { (key, value) ->
                    val commentKey = key.contains("comment", ignoreCase = true)
                    collect(value, inCommentContext || commentKey, out)
                }
            }
            else -> Unit
        }
    }

    private fun authorOf(o: JsonObject): String? {
        fun name(x: JsonElement?): String? = when (x) {
            is JsonObject -> listOf("username", "alternateName", "name").firstNotNullOfOrNull {
                (x[it] as? JsonPrimitive)?.contentOrNull
            }
            is JsonPrimitive -> x.contentOrNull
            else -> null
        }
        return name(o["owner"]) ?: name(o["user"]) ?: name(o["author"])
    }

    /** Decodes a JSON string body ("https:\/\/…&…") or an HTML attribute (&amp;). */
    private fun unescape(raw: String): String {
        val jsonDecoded = runCatching { json.decodeFromString<String>("\"$raw\"") }.getOrDefault(raw)
        return jsonDecoded.replace("&amp;", "&")
    }
}
