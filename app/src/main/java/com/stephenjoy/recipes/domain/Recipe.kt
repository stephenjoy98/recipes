package com.stephenjoy.recipes.domain

import kotlinx.serialization.Serializable

@Serializable
data class Ingredient(
    /** Amount in [unit]s. Null for "to taste" style ingredients that can't be scaled. */
    val quantity: Double? = null,
    val unit: String = "",
    val name: String = "",
    /** Free-text extras such as "finely chopped" or "room temperature". */
    val note: String = "",
)

@Serializable
data class Nutrition(
    val calories: Double? = null,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val sodiumMg: Double? = null,
) {
    val isEmpty: Boolean
        get() = listOf(calories, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, sodiumMg)
            .all { it == null }
}

enum class SourcePlatform(val label: String) {
    YOUTUBE("YouTube"),
    YOUTUBE_SHORTS("YouTube Shorts"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    FACEBOOK("Facebook"),
    PINTEREST("Pinterest"),
    WEB("Web"),
    MANUAL("Manual"),
}

/**
 * A recipe as the app works with it. [uid] is a random, device-independent id used to match
 * recipes across devices during cloud backup; [id] is the local database row id.
 */
@Serializable
data class Recipe(
    val id: Long = 0,
    val uid: String = "",
    val title: String = "",
    val description: String = "",
    val sourceUrl: String = "",
    val sourcePlatform: SourcePlatform = SourcePlatform.MANUAL,
    val author: String = "",
    val imageUrl: String = "",
    val servings: Int = 2,
    val prepTimeMinutes: Int = 0,
    val cookTimeMinutes: Int = 0,
    val category: String = "",
    val tags: List<String> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Nutrition per serving. */
    val nutrition: Nutrition = Nutrition(),
    val notes: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val totalTimeMinutes: Int get() = prepTimeMinutes + cookTimeMinutes
}

object Categories {
    val defaults = listOf(
        "Breakfast", "Lunch", "Dinner", "Snack", "Dessert", "Appetizer",
        "Salad", "Soup", "Drink", "Baking", "Side", "Sauce",
    )
}

/** Normalises a user-entered tag: trimmed, lower-case, no leading '#', single spaces. */
fun normalizeTag(raw: String): String =
    raw.trim().removePrefix("#").trim().lowercase().replace(Regex("\\s+"), " ")

fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
