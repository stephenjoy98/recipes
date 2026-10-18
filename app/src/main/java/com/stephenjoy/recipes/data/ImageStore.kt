package com.stephenjoy.recipes.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Keeps a local copy of each recipe's photo. Social media CDN links expire after a while, so
 * thumbnails are downloaded once at import time; photos picked from the gallery live here too.
 */
class ImageStore(private val context: Context, private val client: OkHttpClient) {

    private val dir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun fileFor(recipeUid: String): File = File(dir, "$recipeUid.jpg")

    /** What the UI should load: the local copy if we have one, otherwise the remote URL. */
    fun model(recipeUid: String, remoteUrl: String): Any? {
        val f = fileFor(recipeUid)
        return when {
            recipeUid.isNotBlank() && f.exists() -> f
            remoteUrl.isNotBlank() -> remoteUrl
            else -> null
        }
    }

    suspend fun download(recipeUid: String, url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank() || recipeUid.isBlank()) return@withContext false
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                fileFor(recipeUid).outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)
    }

    suspend fun copyFrom(recipeUid: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                fileFor(recipeUid).outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
    }

    fun delete(recipeUid: String) {
        if (recipeUid.isNotBlank()) fileFor(recipeUid).delete()
    }
}
