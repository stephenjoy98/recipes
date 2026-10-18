package com.stephenjoy.recipes.export

import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.displayAmount
import com.stephenjoy.recipes.domain.formatDuration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val description: String,
) {
    JSON("JSON", "json", "application/json", "schema.org Recipe data. Imports into Mealie, Tandoor, Nextcloud Cookbook and others"),
    CSV("CSV", "csv", "text/csv", "Spreadsheet with one row per recipe (Excel, Google Sheets)"),
    PDF("PDF", "pdf", "application/pdf", "Printable pages, one recipe per page"),
    TXT("Text", "txt", "text/plain", "Plain text that works anywhere"),
    COOK("Cooklang", "cook", "application/octet-stream", "Cooklang .cook files for Cook CLI and other Cooklang apps"),
}

/** Text-based export formats. Pure Kotlin so they can be unit tested; PDF lives in [PdfRecipeRenderer]. */
object RecipeFormats {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    // ------------------------------------------------------------------ JSON

    /**
     * schema.org/Recipe JSON-LD, the format most recipe managers import. A single recipe is an
     * object and several are an array. The app's own full data rides along under "recipeBox"
     * so nothing is lost on a round trip.
     */
    fun toJson(recipes: List<Recipe>): String {
        val objects = recipes.map(::schemaOrg)
        return if (objects.size == 1) json.encodeToString(JsonObject.serializer(), objects.first())
        else json.encodeToString(JsonArray.serializer(), JsonArray(objects))
    }

    private fun schemaOrg(r: Recipe): JsonObject = buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "Recipe")
        put("name", r.title)
        if (r.description.isNotBlank()) put("description", r.description)
        if (r.author.isNotBlank()) putJsonObject("author") { put("@type", "Person"); put("name", r.author) }
        if (r.imageUrl.isNotBlank()) put("image", r.imageUrl)
        if (r.sourceUrl.isNotBlank()) put("url", r.sourceUrl)
        put("recipeYield", "${r.servings} servings")
        if (r.prepTimeMinutes > 0) put("prepTime", isoDuration(r.prepTimeMinutes))
        if (r.cookTimeMinutes > 0) put("cookTime", isoDuration(r.cookTimeMinutes))
        if (r.totalTimeMinutes > 0) put("totalTime", isoDuration(r.totalTimeMinutes))
        if (r.category.isNotBlank()) put("recipeCategory", r.category)
        if (r.tags.isNotEmpty()) put("keywords", r.tags.joinToString(", "))
        putJsonArray("recipeIngredient") { r.ingredients.forEach { add(ingredientLine(it)) } }
        putJsonArray("recipeInstructions") {
            r.steps.forEach { step -> add(buildJsonObject { put("@type", "HowToStep"); put("text", step) }) }
        }
        if (!r.nutrition.isEmpty) {
            putJsonObject("nutrition") {
                put("@type", "NutritionInformation")
                val n = r.nutrition
                n.calories?.let { put("calories", "${it.roundToInt()} kcal") }
                n.proteinGrams?.let { put("proteinContent", "${num(it)} g") }
                n.carbsGrams?.let { put("carbohydrateContent", "${num(it)} g") }
                n.fatGrams?.let { put("fatContent", "${num(it)} g") }
                n.fiberGrams?.let { put("fiberContent", "${num(it)} g") }
                n.sugarGrams?.let { put("sugarContent", "${num(it)} g") }
                n.sodiumMg?.let { put("sodiumContent", "${it.roundToInt()} mg") }
            }
        }
        if (r.createdAt > 0) put("dateCreated", isoDate(r.createdAt))
        if (r.updatedAt > 0) put("dateModified", isoDate(r.updatedAt))
        put("recipeBox", json.encodeToJsonElement(Recipe.serializer(), r.copy(id = 0)))
    }

    // ------------------------------------------------------------------- CSV

    private val csvHeader = listOf(
        "Title", "Description", "Category", "Tags", "Servings", "Prep time (min)", "Cook time (min)",
        "Total time (min)", "Ingredients", "Steps", "Calories", "Protein (g)", "Carbs (g)", "Fat (g)",
        "Fiber (g)", "Sugar (g)", "Sodium (mg)", "Notes", "Author", "Source", "Favorite",
    )

    /** RFC 4180 CSV with a UTF-8 BOM so Excel shows accents and fractions correctly. */
    fun toCsv(recipes: List<Recipe>): String = buildString {
        append('\uFEFF')
        appendCsvRow(csvHeader)
        recipes.forEach { r ->
            val n = r.nutrition
            appendCsvRow(
                listOf(
                    r.title, r.description, r.category, r.tags.joinToString("; "), r.servings.toString(),
                    r.prepTimeMinutes.toString(), r.cookTimeMinutes.toString(), r.totalTimeMinutes.toString(),
                    r.ingredients.joinToString("\n") { ingredientLine(it) },
                    r.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                    n.calories.cell(), n.proteinGrams.cell(), n.carbsGrams.cell(), n.fatGrams.cell(),
                    n.fiberGrams.cell(), n.sugarGrams.cell(), n.sodiumMg.cell(),
                    r.notes, r.author, r.sourceUrl, if (r.favorite) "yes" else "no",
                ),
            )
        }
    }

    private fun StringBuilder.appendCsvRow(cells: List<String>) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) append(',')
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                append('"').append(cell.replace("\"", "\"\"")).append('"')
            } else {
                append(cell)
            }
        }
        append("\r\n")
    }

    private fun Double?.cell(): String = this?.let(::num).orEmpty()

    // ------------------------------------------------------------------- TXT

    fun toText(recipes: List<Recipe>): String =
        recipes.joinToString("\n\n" + "=".repeat(40) + "\n\n") { textOf(it) }

    fun textOf(r: Recipe, ingredients: List<Ingredient> = r.ingredients, servingsLabel: String = r.servings.toString()): String =
        buildString {
            appendLine(r.title)
            appendLine("-".repeat(r.title.length.coerceIn(3, 40)))
            if (r.description.isNotBlank()) appendLine(r.description).appendLine()
            appendLine(metaLine(r, servingsLabel))
            if (r.category.isNotBlank()) appendLine("Category: ${r.category}")
            if (r.tags.isNotEmpty()) appendLine("Tags: " + r.tags.joinToString(", ") { "#$it" })
            appendLine()
            appendLine("INGREDIENTS")
            ingredients.forEach { appendLine("• " + ingredientLine(it)) }
            if (r.steps.isNotEmpty()) {
                appendLine()
                appendLine("STEPS")
                r.steps.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            }
            nutritionLines(r.nutrition).takeIf { it.isNotEmpty() }?.let { lines ->
                appendLine()
                appendLine("NUTRITION (per serving)")
                lines.forEach { appendLine(it) }
            }
            if (r.notes.isNotBlank()) {
                appendLine()
                appendLine("NOTES")
                appendLine(r.notes)
            }
            if (r.sourceUrl.isNotBlank()) {
                appendLine()
                appendLine("Source: ${r.sourceUrl}" + if (r.author.isNotBlank()) " (${r.author})" else "")
            }
        }.trimEnd() + "\n"

    // -------------------------------------------------------------- Cooklang

    /**
     * Cooklang (https://cooklang.org): YAML front matter for metadata, and ingredients marked up
     * inline as `@name{quantity%unit}`. Each ingredient is tagged where a step first mentions it;
     * any that no step mentions go into a leading "gather" step, so every ingredient is included.
     */
    fun toCooklang(r: Recipe): String = buildString {
        appendLine("---")
        appendLine("title: ${yaml(r.title)}")
        if (r.description.isNotBlank()) appendLine("description: ${yaml(r.description)}")
        appendLine("servings: ${r.servings}")
        if (r.category.isNotBlank()) appendLine("course: ${yaml(r.category)}")
        if (r.tags.isNotEmpty()) appendLine("tags: [" + r.tags.joinToString(", ") { yaml(it) } + "]")
        if (r.prepTimeMinutes > 0) appendLine("prep time: ${r.prepTimeMinutes} minutes")
        if (r.cookTimeMinutes > 0) appendLine("cook time: ${r.cookTimeMinutes} minutes")
        if (r.author.isNotBlank()) appendLine("author: ${yaml(r.author)}")
        if (r.sourceUrl.isNotBlank()) appendLine("source: ${yaml(r.sourceUrl)}")
        if (r.imageUrl.startsWith("http")) appendLine("image: ${yaml(r.imageUrl)}")
        appendLine("---")
        appendLine()

        val steps = r.steps.map(::cooklangText).toMutableList()
        val unplaced = mutableListOf<String>()
        r.ingredients.forEach { ing ->
            val name = cooklangName(ing.name)
            if (name.isBlank()) return@forEach
            val pattern = Regex("(?<![\\w@])" + Regex.escape(name) + "(?!\\w)", RegexOption.IGNORE_CASE)
            var placed = false
            for (i in steps.indices) {
                val step = steps[i]
                val taken = ANNOTATION.findAll(step).map { it.range }.toList()
                val match = pattern.findAll(step).firstOrNull { m -> taken.none { it.first <= m.range.last && m.range.first <= it.last } }
                    ?: continue
                steps[i] = step.replaceRange(match.range, annotate(ing, match.value))
                placed = true
                break
            }
            if (!placed) unplaced += annotate(ing, name)
        }
        if (unplaced.isNotEmpty()) steps.add(0, "Gather " + unplaced.joinToString(", ") + ".")
        steps.forEach { appendLine(it).appendLine() }

        val n = nutritionLines(r.nutrition)
        if (r.notes.isNotBlank() || n.isNotEmpty()) {
            if (n.isNotEmpty()) {
                appendLine("-- Nutrition per serving (estimate)")
                n.forEach { appendLine("-- $it") }
            }
            r.notes.lines().filter { it.isNotBlank() }.forEach { appendLine("-- ${it.trim()}") }
        }
    }.trimEnd() + "\n"

    private val ANNOTATION = Regex("@[^@{}\\n]*\\{[^}\\n]*\\}(?:\\([^)\\n]*\\))?")

    private fun annotate(ing: Ingredient, shownName: String): String {
        val name = cooklangName(shownName)
        val amount = when {
            ing.quantity == null -> ""
            ing.unit.isBlank() -> num(ing.quantity)
            else -> num(ing.quantity) + "%" + cooklangName(ing.unit)
        }
        val note = ing.note.takeIf { it.isNotBlank() }?.let { "(" + it.replace(Regex("[()\\n]"), " ").trim() + ")" }.orEmpty()
        return "@$name{$amount}$note"
    }

    /** Characters that have meaning in Cooklang are dropped from names and units. */
    private fun cooklangName(s: String) = s.replace(Regex("[@#~{}%()\\[\\]\\n]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Free text must not contain Cooklang markers, or parsers would read them as ingredients/cookware/timers. */
    private fun cooklangText(s: String) = s
        .replace("@", " at ").replace("#", "No. ").replace("~", "about ")
        .replace(Regex("[ \\t]+"), " ").replace("\n", " ").trim()

    private fun yaml(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    // --------------------------------------------------------------- shared

    fun ingredientLine(i: Ingredient): String =
        listOf(i.displayAmount(), i.name).filter { it.isNotBlank() }.joinToString(" ") +
            if (i.note.isNotBlank()) ", ${i.note}" else ""

    fun metaLine(r: Recipe, servingsLabel: String = r.servings.toString()): String =
        "Serves $servingsLabel · Prep ${formatDuration(r.prepTimeMinutes)} · Cook ${formatDuration(r.cookTimeMinutes)} · " +
            "Total ${formatDuration(r.totalTimeMinutes)}"

    fun nutritionLines(n: Nutrition): List<String> = listOfNotNull(
        n.calories?.let { "Calories: ${it.roundToInt()} kcal" },
        n.proteinGrams?.let { "Protein: ${num(it)} g" },
        n.carbsGrams?.let { "Carbohydrates: ${num(it)} g" },
        n.fatGrams?.let { "Fat: ${num(it)} g" },
        n.fiberGrams?.let { "Fiber: ${num(it)} g" },
        n.sugarGrams?.let { "Sugar: ${num(it)} g" },
        n.sodiumMg?.let { "Sodium: ${it.roundToInt()} mg" },
    )

    fun isoDuration(minutes: Int): String =
        "PT" + (if (minutes >= 60) "${minutes / 60}H" else "") + (if (minutes % 60 > 0 || minutes == 0) "${minutes % 60}M" else "")

    /** ISO-8601 in UTC (the 'X' pattern letter isn't available before Android 7). */
    private fun isoDate(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))

    /** Up to two decimals, no trailing zeros, always a '.' decimal separator. */
    fun num(v: Double): String {
        val r = (v * 100).roundToInt() / 100.0
        return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }

    /** A safe file name from a recipe title. */
    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "recipe" }

    /** Zips text files, de-duplicating names ("Soup.cook", "Soup (2).cook"). */
    fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val used = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                var candidate = name
                var n = 2
                while (!used.add(candidate.lowercase())) candidate = "$base ($n).$ext".also { n++ }
                zip.putNextEntry(ZipEntry(candidate))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
