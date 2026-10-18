package com.stephenjoy.recipes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: Long): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE uid = :uid")
    suspend fun getByUid(uid: String): RecipeEntity?

    @Query("SELECT * FROM recipes")
    suspend fun getAll(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE sourceUrl = :url LIMIT 1")
    suspend fun findBySource(url: String): RecipeEntity?

    @Query("SELECT tags FROM recipes")
    fun observeTagLists(): Flow<List<String>>

    @Query("SELECT DISTINCT category FROM recipes WHERE category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recipe: RecipeEntity): Long

    @Update
    suspend fun update(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM recipes WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)

    @Query("UPDATE recipes SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: DeletedRecipeEntity)

    @Query("SELECT * FROM deleted_recipes")
    suspend fun getTombstones(): List<DeletedRecipeEntity>

    @Query("DELETE FROM deleted_recipes WHERE uid = :uid")
    suspend fun clearTombstone(uid: String)

    @Transaction
    suspend fun deleteWithTombstone(id: Long, now: Long) {
        val existing = get(id) ?: return
        delete(id)
        insertTombstone(DeletedRecipeEntity(existing.uid, now))
    }
}
