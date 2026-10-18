package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.ai.CloudGemini
import com.stephenjoy.recipes.ai.OnDeviceGemini
import com.stephenjoy.recipes.ai.RecipePrompts
import com.stephenjoy.recipes.data.AiEngine
import com.stephenjoy.recipes.data.SettingsRepository
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.SourcePlatform
import kotlin.coroutines.cancellation.CancellationException

enum class ImportStep(val label: String) {
    FETCHING("Opening the post"),
    WATCHING("Watching the video"),
    READING("Reading the recipe with Gemini"),
    NUTRITION("Estimating nutrition"),
    DONE("Done"),
}

data class ImportResult(
    val recipe: Recipe,
    /** Which model produced it, e.g. "Gemini Nano (on device)". Empty when no AI was used. */
    val engineLabel: String,
    val warnings: List<String>,
)

/**
 * Pipeline: fetch the post's text (caption, description, captions, schema.org data) ->
 * structure it with Gemini -> fill in nutrition. Each stage degrades gracefully, so the user
 * always ends up with at least a draft they can finish by hand.
 */
class RecipeImporter(
    private val fetcher: ContentFetcher,
    private val videoAnalyzer: VideoAnalyzer,
    private val nano: OnDeviceGemini,
    private val cloud: CloudGemini,
    private val settings: SettingsRepository,
) {

    suspend fun import(url: String, onStep: (ImportStep) -> Unit = {}): ImportResult {
        val warnings = mutableListOf<String>()
        onStep(ImportStep.FETCHING)
        var source = try {
            fetcher.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "Couldn't open the link (${e.message}). Gemini will try with the link alone."
            SourceContent(url = url, platform = LinkResolver.platformOf(url))
        }

        val prefs = settings.current()
        val useNano = prefs.aiEngine != AiEngine.CLOUD && nano.isAvailable()
        val useCloud = prefs.aiEngine != AiEngine.ON_DEVICE && cloud.isConfigured()
        val isYouTube = source.platform == SourcePlatform.YOUTUBE || source.platform == SourcePlatform.YOUTUBE_SHORTS

        var recipe: Recipe? = source.structuredRecipe?.takeIf { it.ingredients.isNotEmpty() }
        var engine = if (recipe != null) "Recipe card on the page" else ""

        // Instagram reels: download the clip, read its on-screen text on device, and keep the
        // bytes so cloud Gemini can watch and listen to it.
        var clip: VideoClip? = null
        if (recipe == null && source.videoUrl.isNotBlank() && prefs.analyzeVideos && (useNano || useCloud)) {
            onStep(ImportStep.WATCHING)
            clip = attempt(warnings, "Video") { videoAnalyzer.analyze(source.videoUrl) }
            clip?.onScreenText?.takeIf { it.isNotBlank() }?.let { source = source.copy(onScreenText = it) }
        }

        if (recipe == null) {
            onStep(ImportStep.READING)
            var nanoRecipe: Recipe? = null
            if (useNano && source.hasText) {
                nanoRecipe = attempt(warnings, "Gemini Nano") {
                    RecipePrompts.parseRecipe(nano.generate(RecipePrompts.recipePrompt(source, nano.maxInputChars)))
                }?.takeIf { it.isUsable() }
            }
            // A complete on-device result is kept (private, no upload). Otherwise cloud Gemini gets
            // a go, watching the video when we have one.
            if (nanoRecipe != null && (nanoRecipe.isComplete() || !useCloud)) {
                recipe = nanoRecipe
                engine = "Gemini Nano (on device)"
            } else if (useCloud) {
                val youtube = if (isYouTube) LinkResolver.youtubeVideoId(source.url)?.let(LinkResolver::youtubeWatchUrl) else null
                val videoBytes = clip?.bytes
                val tries = buildList {
                    if (videoBytes != null) add(CloudTry(video = videoBytes))
                    if (youtube != null && !source.hasText) add(CloudTry(youtubeUrl = youtube))
                    if (source.hasText) add(CloudTry())
                    if (youtube != null && source.hasText) add(CloudTry(youtubeUrl = youtube))
                }
                for (t in tries) {
                    val watching = t.video != null || t.youtubeUrl != null
                    recipe = attempt(warnings, "Gemini cloud") {
                        RecipePrompts.parseRecipe(
                            cloud.generate(
                                RecipePrompts.fullPrompt(source, cloud.maxInputChars, withVideo = watching),
                                prefs.cloudModel,
                                youtubeUrl = t.youtubeUrl,
                                video = t.video,
                            ),
                        )
                    }?.takeIf { it.isUsable() }
                    if (recipe != null) {
                        engine = if (watching) "Gemini (watched the video)" else "Gemini (cloud)"
                        break
                    }
                }
                if (recipe == null && nanoRecipe != null) {
                    recipe = nanoRecipe
                    engine = "Gemini Nano (on device)"
                }
            }
            if (recipe == null) {
                warnings += when {
                    !useNano && !useCloud ->
                        "No Gemini model is available. Turn on Gemini Nano in Settings, or add Firebase for cloud Gemini."
                    !source.hasText -> "The post had no readable caption, so there was nothing to extract."
                    else -> "Gemini couldn't find a recipe in this post. Fill in the details below."
                }
            }
        }

        var result = (recipe ?: Recipe(notes = source.text.take(4_000))).withSource(source)

        if (result.ingredients.isNotEmpty() && result.nutrition.isEmpty && (useNano || useCloud)) {
            onStep(ImportStep.NUTRITION)
            val prompt = RecipePrompts.nutritionPrompt(result)
            val nutrition = (if (useNano) attempt(warnings, "Gemini Nano") { RecipePrompts.parseNutrition(nano.generate(prompt)) } else null)
                ?: (if (useCloud) attempt(warnings, "Gemini cloud") { RecipePrompts.parseNutrition(cloud.generate(prompt, prefs.cloudModel)) } else null)
            if (nutrition != null) result = result.copy(nutrition = nutrition)
        }

        onStep(ImportStep.DONE)
        // Only surface model errors if we ended up without a recipe.
        val shown = if (recipe != null) warnings.filterNot { it.startsWith("Gemini") } else warnings
        return ImportResult(result, engine, shown.distinct())
    }

    /**
     * Structures free text (e.g. a PDF or note from another app) with Gemini. Returns null when
     * no model is available or nothing recipe-like was found.
     */
    suspend fun fromText(text: String, fallbackTitle: String): Recipe? {
        val source = SourceContent(url = "", platform = SourcePlatform.MANUAL, title = fallbackTitle, text = text)
        val prefs = settings.current()
        val useNano = prefs.aiEngine != AiEngine.CLOUD && nano.isAvailable()
        val useCloud = prefs.aiEngine != AiEngine.ON_DEVICE && cloud.isConfigured()
        val ignored = mutableListOf<String>()
        var recipe = if (useNano) attempt(ignored, "Gemini Nano") {
            RecipePrompts.parseRecipe(nano.generate(RecipePrompts.recipePrompt(source, nano.maxInputChars)))
        }?.takeIf { it.isUsable() } else null
        if (recipe == null && useCloud) recipe = attempt(ignored, "Gemini cloud") {
            RecipePrompts.parseRecipe(cloud.generate(RecipePrompts.fullPrompt(source, cloud.maxInputChars, withVideo = false), prefs.cloudModel))
        }?.takeIf { it.isUsable() }
        return recipe?.let { it.copy(title = it.title.ifBlank { fallbackTitle }, sourcePlatform = SourcePlatform.MANUAL) }
    }

    private class CloudTry(val video: ByteArray? = null, val youtubeUrl: String? = null)

    private fun Recipe.isUsable() = ingredients.size >= 2 || steps.size >= 2

    /** Looks like a full recipe: several ingredients, most with amounts, and real steps. */
    private fun Recipe.isComplete() =
        ingredients.size >= 3 && ingredients.count { it.quantity != null } * 2 >= ingredients.size && steps.size >= 2

    private fun Recipe.withSource(source: SourceContent) = copy(
        title = title.ifBlank { source.title.ifBlank { "Recipe from ${source.platform.label}" } },
        sourceUrl = source.url,
        sourcePlatform = source.platform,
        author = author.ifBlank { source.author },
        imageUrl = imageUrl.ifBlank { source.imageUrl },
    )

    private suspend fun <T> attempt(warnings: MutableList<String>, who: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "$who: ${e.message ?: e::class.simpleName}"
            null
        }
}
