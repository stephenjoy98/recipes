package com.stephenjoy.recipes.ai

import com.stephenjoy.recipes.domain.Categories
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.IngredientParser
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.displayAmount
import com.stephenjoy.recipes.domain.normalizeTag
import com.stephenjoy.recipes.extract.SourceContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Prompts and tolerant parsing of the model's JSON answers. Pure Kotlin so it is unit-testable. */
object RecipePrompts {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val categoryList = Categories.defaults.joinToString(", ")

    private const val RECIPE_SCHEMA = """{"title":string,"description":string (one sentence),"servings":int,"prepTimeMinutes":int,"cookTimeMinutes":int,"category":string,"tags":[string],"ingredients":[{"quantity":number|null,"unit":string,"name":string,"note":string}],"steps":[string]}"""
    private const val NUTRITION_SCHEMA = """{"calories":number,"proteinGrams":number,"carbsGrams":number,"fatGrams":number,"fiberGrams":number,"sugarGrams":number,"sodiumMg":number}"""

    /** Recipe extraction prompt (no nutrition) — kept short for Gemini Nano's context window. */
    fun recipePrompt(source: SourceContent, maxChars: Int): String = buildString {
        appendLine("You turn social media cooking posts into structured recipes.")
        appendLine("Read the post below and reply with ONLY minified JSON matching:")
        appendLine(RECIPE_SCHEMA)
        appendRules()
        appendSource(source, maxChars)
    }

    /** Single-shot prompt for the cloud model: recipe + nutrition, optionally watching the video. */
    fun fullPrompt(source: SourceContent, maxChars: Int, withVideo: Boolean): String = buildString {
        appendLine("You turn social media cooking posts and videos into structured recipes.")
        if (withVideo) appendLine("Watch the attached video; use what is shown and said as well as the text below.")
        appendLine("Reply with ONLY JSON matching:")
        appendLine(RECIPE_SCHEMA.dropLast(1) + ""","nutrition":$NUTRITION_SCHEMA}""")
        appendRules()
        appendLine("- nutrition is your best estimate PER SERVING from the ingredients.")
        appendSource(source, maxChars)
    }

    fun nutritionPrompt(recipe: Recipe): String = buildString {
        appendLine("Estimate nutrition PER SERVING for this recipe. Reply with ONLY minified JSON matching:")
        appendLine(NUTRITION_SCHEMA)
        appendLine("Use typical values for the ingredients; numbers only, no units.")
        appendLine("Recipe: ${recipe.title}")
        appendLine("Servings: ${recipe.servings}")
        appendLine("Ingredients:")
        recipe.ingredients.forEach { appendLine("- ${it.displayAmount()} ${it.name}".replace(Regex("\\s+"), " ")) }
    }

    fun tagsPrompt(recipe: Recipe, existingTags: List<String>): String = buildString {
        appendLine("Suggest 5 short lowercase tags for this recipe (cuisine, diet, main ingredient, meal type, technique).")
        if (existingTags.isNotEmpty()) appendLine("Prefer reusing these existing tags when they fit: ${existingTags.take(40).joinToString(", ")}")
        appendLine("Reply with ONLY minified JSON: {\"tags\":[string]}")
        appendLine("Recipe: ${recipe.title}")
        if (recipe.category.isNotBlank()) appendLine("Category: ${recipe.category}")
        appendLine("Ingredients: " + recipe.ingredients.joinToString(", ") { it.name })
    }

    @Serializable
    private data class AiTags(val tags: List<String>? = null)

    fun parseTags(answer: String): List<String> =
        decode<AiTags>(answer)?.tags.orEmpty().map(::normalizeTag).filter { it.isNotBlank() && it.length <= 30 }.distinct()

    private fun StringBuilder.appendRules() {
        appendLine("Rules:")
        appendLine("- quantity is a number (convert fractions: 1/2 -> 0.5); use null for 'to taste'.")
        appendLine("- unit is short (g, ml, cup, tbsp, tsp, oz, lb, clove...) or \"\" for counted items like eggs.")
        appendLine("- steps are short imperative sentences in order, without numbering.")
        appendLine("- category is one of: $categoryList.")
        appendLine("- tags: 3 to 6 lowercase tags (cuisine, diet, main ingredient, technique), no '#'.")
        appendLine("- If times or servings are not stated, estimate sensible values.")
        appendLine("- Ignore hashtags, sponsorships and calls to like/follow.")
    }

    private fun StringBuilder.appendSource(source: SourceContent, maxChars: Int) {
        appendLine()
        appendLine("Platform: ${source.platform.label}")
        if (source.title.isNotBlank()) appendLine("Title: ${source.title}")
        if (source.author.isNotBlank()) appendLine("Creator: ${source.author}")
        val textBudget = if (source.transcript.isBlank()) maxChars else maxChars / 2
        if (source.text.isNotBlank()) {
            appendLine("Caption/description:")
            appendLine(source.text.take(textBudget))
        }
        if (source.transcript.isNotBlank()) {
            appendLine("Transcript:")
            appendLine(source.transcript.take(maxChars - minOf(source.text.length, textBudget)))
        }
    }

    // ------------------------------------------------------------------ parsing

    @Serializable
    private data class AiIngredient(
        val quantity: JsonElement? = null,
        val unit: String? = null,
        val name: String? = null,
        val note: String? = null,
    )

    @Serializable
    private data class AiNutrition(
        val calories: JsonElement? = null,
        val proteinGrams: JsonElement? = null,
        val carbsGrams: JsonElement? = null,
        val fatGrams: JsonElement? = null,
        val fiberGrams: JsonElement? = null,
        val sugarGrams: JsonElement? = null,
        val sodiumMg: JsonElement? = null,
    )

    @Serializable
    private data class AiRecipe(
        val title: String? = null,
        val description: String? = null,
        val servings: JsonElement? = null,
        val prepTimeMinutes: JsonElement? = null,
        val cookTimeMinutes: JsonElement? = null,
        val category: String? = null,
        val tags: List<String>? = null,
        val ingredients: List<AiIngredient>? = null,
        val steps: List<String>? = null,
        val nutrition: AiNutrition? = null,
    )

    /** Parses the model's answer, tolerating ```json fences, prose around the JSON and loose numbers. */
    fun parseRecipe(answer: String): Recipe? {
        val ai = decode<AiRecipe>(answer) ?: return null
        val ingredients = ai.ingredients.orEmpty().mapNotNull { it.toIngredient() }
        return Recipe(
            title = ai.title.orEmpty().trim(),
            description = ai.description.orEmpty().trim(),
            servings = number(ai.servings)?.toInt()?.coerceIn(1, 100) ?: 2,
            prepTimeMinutes = number(ai.prepTimeMinutes)?.toInt()?.coerceIn(0, 10_000) ?: 0,
            cookTimeMinutes = number(ai.cookTimeMinutes)?.toInt()?.coerceIn(0, 10_000) ?: 0,
            category = matchCategory(ai.category.orEmpty()),
            tags = ai.tags.orEmpty().map(::normalizeTag).filter { it.isNotBlank() }.distinct().take(8),
            ingredients = ingredients,
            steps = ai.steps.orEmpty().map { it.trim().replace(Regex("^(?:step\\s*)?\\d+[.):-]\\s*", RegexOption.IGNORE_CASE), "") }
                .filter { it.isNotBlank() },
            nutrition = ai.nutrition?.toNutrition() ?: Nutrition(),
        )
    }

    fun parseNutrition(answer: String): Nutrition? = decode<AiNutrition>(answer)?.toNutrition()?.takeUnless { it.isEmpty }

    private inline fun <reified T> decode(answer: String): T? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString<T>(answer.substring(start, end + 1)) }.getOrNull()
    }

    private fun AiIngredient.toIngredient(): Ingredient? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        val q = when (val raw = quantity) {
            is JsonPrimitive -> raw.doubleOrNull ?: raw.content.let { com.stephenjoy.recipes.domain.Quantities.parse(it) }
            else -> null
        }
        // Some answers stuff the whole line into "name"; fall back to our own parser then.
        if (q == null && unit.isNullOrBlank() && n.first().isDigit()) return IngredientParser.parse(n)
        return Ingredient(quantity = q?.takeIf { it > 0 }, unit = unit?.trim().orEmpty(), name = n, note = note?.trim().orEmpty())
    }

    private fun AiNutrition.toNutrition() = Nutrition(
        calories = number(calories),
        proteinGrams = number(proteinGrams),
        carbsGrams = number(carbsGrams),
        fatGrams = number(fatGrams),
        fiberGrams = number(fiberGrams),
        sugarGrams = number(sugarGrams),
        sodiumMg = number(sodiumMg),
    )

    private fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: Regex("""\d+(?:\.\d+)?""").find(p.content)?.value?.toDoubleOrNull()
    }

    private fun matchCategory(raw: String): String {
        val r = raw.trim()
        if (r.isEmpty()) return ""
        return Categories.defaults.firstOrNull { it.equals(r, ignoreCase = true) }
            ?: Categories.defaults.firstOrNull { r.contains(it, ignoreCase = true) }
            ?: r.replaceFirstChar { it.uppercase() }
    }
}
