package com.stephenjoy.recipes.data.local

import androidx.room.TypeConverter
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.SourcePlatform
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun stringsToJson(value: List<String>): String = json.encodeToString(value)
    @TypeConverter fun jsonToStrings(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter fun ingredientsToJson(value: List<Ingredient>): String = json.encodeToString(value)
    @TypeConverter fun jsonToIngredients(value: String): List<Ingredient> = json.decodeFromString(value)

    @TypeConverter fun nutritionToJson(value: Nutrition): String = json.encodeToString(value)
    @TypeConverter fun jsonToNutrition(value: String): Nutrition = json.decodeFromString(value)

    @TypeConverter fun platformToString(value: SourcePlatform): String = value.name
    @TypeConverter fun stringToPlatform(value: String): SourcePlatform =
        SourcePlatform.entries.firstOrNull { it.name == value } ?: SourcePlatform.WEB
}
