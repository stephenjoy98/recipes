package com.stephenjoy.recipes.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.SourcePlatform

@Entity(tableName = "recipes", indices = [Index(value = ["uid"], unique = true)])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val title: String,
    val description: String,
    val sourceUrl: String,
    val sourcePlatform: SourcePlatform,
    val author: String,
    val imageUrl: String,
    val servings: Int,
    val prepTimeMinutes: Int,
    val cookTimeMinutes: Int,
    val category: String,
    /** Stored as a JSON array; also searchable with LIKE '%"tag"%'. */
    val tags: List<String>,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val nutrition: Nutrition,
    val notes: String,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

fun RecipeEntity.toDomain() = Recipe(
    id = id, uid = uid, title = title, description = description, sourceUrl = sourceUrl,
    sourcePlatform = sourcePlatform, author = author, imageUrl = imageUrl, servings = servings,
    prepTimeMinutes = prepTimeMinutes, cookTimeMinutes = cookTimeMinutes, category = category,
    tags = tags, ingredients = ingredients, steps = steps, nutrition = nutrition, notes = notes,
    favorite = favorite, createdAt = createdAt, updatedAt = updatedAt,
)

fun Recipe.toEntity() = RecipeEntity(
    id = id, uid = uid, title = title, description = description, sourceUrl = sourceUrl,
    sourcePlatform = sourcePlatform, author = author, imageUrl = imageUrl, servings = servings,
    prepTimeMinutes = prepTimeMinutes, cookTimeMinutes = cookTimeMinutes, category = category,
    tags = tags, ingredients = ingredients, steps = steps, nutrition = nutrition, notes = notes,
    favorite = favorite, createdAt = createdAt, updatedAt = updatedAt,
)

/** Remembers recipes deleted locally so the deletion can be propagated to the cloud backup. */
@Entity(tableName = "deleted_recipes")
data class DeletedRecipeEntity(
    @PrimaryKey val uid: String,
    val deletedAt: Long,
)
