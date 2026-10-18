package com.stephenjoy.recipes.data

import com.stephenjoy.recipes.data.local.RecipeDao
import com.stephenjoy.recipes.data.local.toDomain
import com.stephenjoy.recipes.data.local.toEntity
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.normalizeTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

class RecipeRepository(private val dao: RecipeDao) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the user changes data; used to trigger automatic cloud backup. */
    val changes: SharedFlow<Unit> = _changes

    fun observeRecipes(): Flow<List<Recipe>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRecipe(id: Long): Flow<Recipe?> = dao.observe(id).map { it?.toDomain() }

    suspend fun getRecipe(id: Long): Recipe? = dao.get(id)?.toDomain()

    suspend fun findBySourceUrl(url: String): Recipe? = dao.findBySource(url)?.toDomain()

    /** All tags in use, most used first. */
    fun observeTags(): Flow<List<String>> = dao.observeTagLists().map { rows ->
        rows.flatMap { runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    fun observeUsedCategories(): Flow<List<String>> = dao.observeCategories()

    /** Inserts or updates; returns the local id. */
    suspend fun save(recipe: Recipe): Long {
        val now = System.currentTimeMillis()
        val cleaned = recipe.copy(
            uid = recipe.uid.ifBlank { UUID.randomUUID().toString() },
            title = recipe.title.trim().ifBlank { "Untitled recipe" },
            category = recipe.category.trim(),
            tags = recipe.tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct(),
            ingredients = recipe.ingredients.filter { it.name.isNotBlank() },
            steps = recipe.steps.map { it.trim() }.filter { it.isNotBlank() },
            createdAt = if (recipe.createdAt == 0L) now else recipe.createdAt,
            updatedAt = now,
        )
        val id = if (cleaned.id == 0L) {
            dao.insert(cleaned.toEntity())
        } else {
            dao.update(cleaned.toEntity()); cleaned.id
        }
        dao.clearTombstone(cleaned.uid)
        _changes.tryEmit(Unit)
        return id
    }

    suspend fun delete(id: Long) {
        dao.deleteWithTombstone(id, System.currentTimeMillis())
        _changes.tryEmit(Unit)
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        dao.setFavorite(id, favorite, System.currentTimeMillis())
        _changes.tryEmit(Unit)
    }
}
