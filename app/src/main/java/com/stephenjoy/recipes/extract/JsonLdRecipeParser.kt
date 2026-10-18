package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.domain.IngredientParser
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.normalizeTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads schema.org/Recipe JSON-LD, which most recipe websites embed. When present it gives an
 * exact recipe without needing the AI model at all.
 */
object JsonLdRecipeParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(jsonLdBlocks: List<String>): Recipe? {
        for (block in jsonLdBlocks) {
            val root = runCatching { json.parseToJsonElement(block) }.getOrNull() ?: continue
            val node = findRecipe(root) ?: continue
            return toRecipe(node)
        }
        return null
    }

    private fun findRecipe(element: JsonElement): JsonObject? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> when {
            isRecipe(element) -> element
            else -> element["@graph"]?.let { findRecipe(it) }
                ?: element["mainEntity"]?.let { findRecipe(it) }
        }
        else -> null
    }

    private fun isRecipe(obj: JsonObject): Boolean = when (val type = obj["@type"]) {
        is JsonPrimitive -> type.contentOrNull.equals("Recipe", ignoreCase = true)
        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull.equals("Recipe", ignoreCase = true) }
        else -> false
    }

    private fun toRecipe(o: JsonObject): Recipe {
        val ingredients = strings(o["recipeIngredient"] ?: o["ingredients"]).map { IngredientParser.parse(cleanHtml(it)) }
        val keywords = when (val k = o["keywords"]) {
            is JsonPrimitive -> k.contentOrNull.orEmpty().split(',')
            else -> strings(k)
        }
        val categories = strings(o["recipeCategory"])
        val cuisine = strings(o["recipeCuisine"])
        return Recipe(
            title = text(o["name"]).let(::cleanHtml),
            description = text(o["description"]).let(::cleanHtml),
            author = authorName(o["author"]),
            imageUrl = imageUrl(o["image"]),
            servings = servings(o["recipeYield"]),
            prepTimeMinutes = isoMinutes(text(o["prepTime"])),
            cookTimeMinutes = isoMinutes(text(o["cookTime"])).let { cook ->
                if (cook == 0) (isoMinutes(text(o["totalTime"])) - isoMinutes(text(o["prepTime"]))).coerceAtLeast(0) else cook
            },
            category = categories.firstOrNull()?.trim().orEmpty(),
            tags = (keywords + cuisine + categories.drop(1)).map(::normalizeTag).filter { it.isNotBlank() && it.length < 30 }.distinct().take(12),
            ingredients = ingredients.filter { it.name.isNotBlank() },
            steps = instructions(o["recipeInstructions"]).map(::cleanHtml).filter { it.isNotBlank() },
            nutrition = nutrition(o["nutrition"] as? JsonObject),
        )
    }

    private fun text(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonArray -> e.firstOrNull()?.let(::text).orEmpty()
        is JsonObject -> text(e["@value"] ?: e["name"] ?: e["text"])
        else -> ""
    }

    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.map(::text).filter { it.isNotBlank() }
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.isNotBlank() })
        is JsonObject -> listOf(text(e)).filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun authorName(e: JsonElement?): String = when (e) {
        is JsonArray -> e.firstOrNull()?.let(::authorName).orEmpty()
        is JsonObject -> text(e["name"])
        else -> text(e)
    }

    private fun imageUrl(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonArray -> e.firstOrNull()?.let(::imageUrl).orEmpty()
        is JsonObject -> text(e["url"] ?: e["contentUrl"])
        else -> ""
    }

    private fun servings(e: JsonElement?): Int {
        val candidates = when (e) {
            is JsonArray -> e.map(::text)
            else -> listOf(text(e))
        }
        return candidates.firstNotNullOfOrNull { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?.coerceIn(1, 100) ?: 2
    }

    private fun instructions(e: JsonElement?): List<String> = when (e) {
        is JsonPrimitive -> e.contentOrNull.orEmpty()
            .split(Regex("""\n+|(?<=\.)\s+(?=\d+[.)]\s)"""))
            .map { it.trim().replace(Regex("""^\d+[.)]\s*"""), "") }
        is JsonArray -> e.flatMap { instructions(it) }
        is JsonObject -> when {
            e["itemListElement"] != null -> instructions(e["itemListElement"])
            else -> listOf(text(e["text"] ?: e["name"]))
        }
        else -> emptyList()
    }

    private fun nutrition(o: JsonObject?): Nutrition {
        if (o == null) return Nutrition()
        fun num(key: String): Double? =
            Regex("""\d+(?:[.,]\d+)?""").find(text(o[key]))?.value?.replace(',', '.')?.toDoubleOrNull()
        val sodium = num("sodiumContent")?.let { v -> if (text(o["sodiumContent"]).contains(Regex("""\bg\b"""))) v * 1000 else v }
        return Nutrition(
            calories = num("calories"),
            proteinGrams = num("proteinContent"),
            carbsGrams = num("carbohydrateContent"),
            fatGrams = num("fatContent"),
            fiberGrams = num("fiberContent"),
            sugarGrams = num("sugarContent"),
            sodiumMg = sodium,
        )
    }

    /** ISO-8601 duration ("PT1H30M", "P0DT0H20M") to minutes. */
    fun isoMinutes(iso: String): Int {
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""", RegexOption.IGNORE_CASE)
            .find(iso.trim()) ?: return 0
        val (d, h, min) = m.destructured
        return (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0)
    }

    private fun cleanHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ").trim()
}
