package com.stephenjoy.recipes.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stephenjoy.recipes.data.ImageStore
import com.stephenjoy.recipes.data.RecipeRepository
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.extract.RecipeImporter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** A recipe found in a file, plus whether importing it replaces one already in the library. */
data class ImportCandidate(val recipe: Recipe, val existingId: Long?)

data class FileReadResult(
    val fileName: String,
    val candidates: List<ImportCandidate>,
    /** "Gemini" when the file had no recognisable structure and the model extracted the recipe. */
    val usedGemini: Boolean,
)

/**
 * Imports recipes from JSON, CSV, PDF, TXT, Cooklang (.cook) and .zip files. Structured files are
 * parsed directly; PDFs are converted to text first, and text without a recognisable layout is
 * handed to Gemini.
 */
class RecipeFileImporter(
    private val context: Context,
    private val repository: RecipeRepository,
    private val images: ImageStore,
    private val importer: RecipeImporter,
) {
    private val maxBytes = 25L * 1024 * 1024

    suspend fun read(uri: Uri): FileReadResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            require(data.size <= maxBytes) { "That file is too large to import" }
            data
        } ?: error("Couldn't open the file")

        val isPdf = name.endsWith(".pdf", true) || bytes.startsWith("%PDF")
        val text = if (isPdf) pdfText(bytes) else null
        var recipes = if (text != null) RecipeParsers.parseText(text) else RecipeParsers.parse(name, bytes)
        var usedGemini = false
        if (recipes.isEmpty()) {
            // No structure we recognise: let Gemini read plain text / PDF text.
            val plain = text ?: String(bytes, Charsets.UTF_8).takeIf { looksLikeText(it) }
            if (!plain.isNullOrBlank()) {
                importer.fromText(plain.take(60_000), name.substringBeforeLast('.'))?.let {
                    recipes = listOf(it)
                    usedGemini = true
                }
            }
        }
        require(recipes.isNotEmpty()) {
            "No recipes found in $name. Supported: JSON, CSV, PDF, TXT, Cooklang (.cook) and .zip."
        }
        // A file may repeat a recipe id (e.g. two copies); later copies become new recipes.
        val seen = mutableSetOf<String>()
        val unique = recipes.map { r -> if (r.uid.isNotBlank() && !seen.add(r.uid)) r.copy(uid = "") else r }
        FileReadResult(name, unique.map { ImportCandidate(it, repository.existingIdFor(it)) }, usedGemini)
    }

    /** Saves the chosen recipes; returns (added, updated). */
    suspend fun save(candidates: List<ImportCandidate>): Pair<Int, Int> {
        var added = 0
        var updated = 0
        candidates.forEach { c ->
            val recipe = c.recipe.copy(
                id = c.existingId ?: 0,
                uid = c.recipe.uid.ifBlank { UUID.randomUUID().toString() },
                createdAt = if (c.existingId == null) 0 else c.recipe.createdAt,
            )
            repository.save(recipe)
            if (c.existingId == null) added++ else updated++
            if (recipe.imageUrl.startsWith("http") && !images.fileFor(recipe.uid).exists()) {
                images.download(recipe.uid, recipe.imageUrl)
            }
        }
        return added to updated
    }

    private fun pdfText(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context)
        return PDDocument.load(bytes).use { doc -> PDFTextStripper().getText(doc) }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "recipes"

    private fun ByteArray.startsWith(prefix: String) =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun looksLikeText(s: String) = s.isNotBlank() && s.count { it == '�' || it.code < 9 } < s.length / 50 + 1
}
