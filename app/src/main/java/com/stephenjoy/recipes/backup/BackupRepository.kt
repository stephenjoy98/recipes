package com.stephenjoy.recipes.backup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.stephenjoy.recipes.data.SettingsRepository
import com.stephenjoy.recipes.data.local.RecipeDao
import com.stephenjoy.recipes.data.local.toDomain
import com.stephenjoy.recipes.data.local.toEntity
import com.stephenjoy.recipes.domain.Recipe
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

data class SyncResult(val uploaded: Int, val downloaded: Int, val deleted: Int)

/**
 * Two-way sync of the recipe library with Firestore at users/{uid}/recipes/{recipeUid}.
 * Last write wins per recipe (by updatedAt). Deletions are synced as tombstone documents.
 */
class BackupRepository(
    private val auth: AuthManager,
    private val dao: RecipeDao,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private data class Remote(val updatedAt: Long, val deleted: Boolean, val data: String?)

    suspend fun sync(): SyncResult = mutex.withLock {
        val uid = checkNotNull(auth.currentUid) { "Sign in with Google to back up your recipes." }
        val collection = FirebaseFirestore.getInstance().collection("users").document(uid).collection("recipes")

        val remote = collection.get().await().documents.associate { doc ->
            doc.id to Remote(
                updatedAt = doc.getLong("updatedAt") ?: 0,
                deleted = doc.getBoolean("deleted") ?: false,
                data = doc.getString("data"),
            )
        }
        val local = dao.getAll().associateBy { it.uid }
        val tombstones = dao.getTombstones().associateBy { it.uid }

        val uploads = mutableMapOf<String, Map<String, Any>>()
        var downloaded = 0
        var deleted = 0

        for ((recipeUid, entity) in local) {
            val r = remote[recipeUid]
            when {
                r == null || entity.updatedAt > r.updatedAt ->
                    uploads[recipeUid] = document(entity.toDomain())
                r.updatedAt > entity.updatedAt && r.deleted -> {
                    dao.deleteByUid(recipeUid); deleted++
                }
                r.updatedAt > entity.updatedAt -> decode(r.data)?.let {
                    dao.update(it.copy(id = entity.id).toEntity()); downloaded++
                }
            }
        }
        for ((recipeUid, tomb) in tombstones) {
            val r = remote[recipeUid]
            if (r != null && !r.deleted && r.updatedAt <= tomb.deletedAt) {
                uploads[recipeUid] = mapOf("deleted" to true, "updatedAt" to tomb.deletedAt, "data" to "")
            }
        }
        for ((recipeUid, r) in remote) {
            if (recipeUid in local || r.deleted) continue
            val tomb = tombstones[recipeUid]
            if (tomb != null && tomb.deletedAt >= r.updatedAt) continue
            decode(r.data)?.let { dao.insert(it.copy(id = 0).toEntity()); downloaded++ }
        }

        val db = FirebaseFirestore.getInstance()
        uploads.entries.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { (id, doc) -> batch.set(collection.document(id), doc, SetOptions.merge()) }
            batch.commit().await()
        }
        tombstones.keys.forEach { dao.clearTombstone(it) }
        settings.setLastBackupAt(System.currentTimeMillis())
        SyncResult(uploaded = uploads.size, downloaded = downloaded, deleted = deleted)
    }

    private fun document(recipe: Recipe): Map<String, Any> = mapOf(
        "data" to json.encodeToString(Recipe.serializer(), recipe.copy(id = 0)),
        "title" to recipe.title,
        "updatedAt" to recipe.updatedAt,
        "deleted" to false,
    )

    private fun decode(data: String?): Recipe? =
        data?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Recipe.serializer(), it) }.getOrNull() }
}
