package com.stephenjoy.recipes.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stephenjoy.recipes.data.ImageStore
import com.stephenjoy.recipes.domain.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The bytes of an export plus the file name and type to save or share it with. */
class ExportFile(val name: String, val mimeType: String, val bytes: ByteArray)

class RecipeExporter(private val context: Context, private val images: ImageStore) {

    private val pdf = PdfRecipeRenderer { recipe -> images.fileFor(recipe.uid).takeIf { recipe.uid.isNotBlank() && it.exists() } }

    suspend fun export(recipes: List<Recipe>, format: ExportFormat): ExportFile = withContext(Dispatchers.Default) {
        require(recipes.isNotEmpty()) { "Nothing to export" }
        val base = if (recipes.size == 1) RecipeFormats.fileName(recipes.first().title) else "Recipe Box (${recipes.size} recipes)"
        when (format) {
            ExportFormat.JSON -> ExportFile("$base.json", format.mimeType, RecipeFormats.toJson(recipes).toByteArray())
            ExportFormat.CSV -> ExportFile("$base.csv", format.mimeType, RecipeFormats.toCsv(recipes).toByteArray())
            ExportFormat.TXT -> ExportFile("$base.txt", format.mimeType, RecipeFormats.toText(recipes).toByteArray())
            ExportFormat.PDF -> ExportFile("$base.pdf", format.mimeType, pdf.render(recipes))
            // Cooklang is one recipe per file, so several recipes are bundled into a .zip.
            ExportFormat.COOK -> if (recipes.size == 1) {
                ExportFile("$base.cook", format.mimeType, RecipeFormats.toCooklang(recipes.first()).toByteArray())
            } else {
                val files = recipes.map { "${RecipeFormats.fileName(it.title)}.cook" to RecipeFormats.toCooklang(it).toByteArray() }
                ExportFile("$base.zip", "application/zip", RecipeFormats.zip(files))
            }
        }
    }

    /** Writes the export to a location the user picked through the system file picker. */
    suspend fun saveTo(uri: Uri, file: ExportFile) = withContext(Dispatchers.IO) {
        // "wt" truncates an existing file; a few document providers only accept plain "w".
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: error("Couldn't open the file for writing")
        stream.use { it.write(file.bytes) }
    }

    /** Builds a share-sheet intent; the file is served from the app cache through a FileProvider. */
    suspend fun shareIntent(file: ExportFile): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, file.name).apply { writeBytes(file.bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType(file.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name.substringBeforeLast('.'))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        Intent.createChooser(send, "Share ${file.name}")
    }
}
