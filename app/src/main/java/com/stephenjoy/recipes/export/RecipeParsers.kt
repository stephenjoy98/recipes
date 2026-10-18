package com.stephenjoy.recipes.export

import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.IngredientParser
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Quantities
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.SourcePlatform
import com.stephenjoy.recipes.domain.normalizeTag
import com.stephenjoy.recipes.extract.JsonLdRecipeParser
import com.stephenjoy.recipes.extract.LinkResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads recipes back from the export formats, and from similar files made by other apps:
 * schema.org JSON (Mealie, Tandoor, Nextcloud Cookbook…), CSV with recognisable headers, plain
 * text with Ingredients/Steps sections, and Cooklang. Pure Kotlin so it is unit-testable.
 */
object RecipeParsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Picks a parser from the file name, falling back to sniffing the content. */
    fun parse(fileName: String, bytes: ByteArray): List<Recipe> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "zip" || isZip(bytes)) return parseZip(bytes)
        val text = decode(bytes)
        return when (ext) {
            "json", "jsonld" -> parseJson(text)
            "csv", "tsv" -> parseCsv(text)
            "cook", "cooklang" -> listOfNotNull(parseCooklang(text, fileName.substringBeforeLast('.')))
            "txt", "text", "md" -> parseText(text)
            else -> sniff(text, fileName)
        }
    }

    private fun sniff(text: String, fileName: String): List<Recipe> {
        val t = text.trimStart()
        return when {
            t.startsWith("{") || t.startsWith("[") -> parseJson(text)
            t.startsWith("---") || t.startsWith(">>") || Regex("@[^\\s@]+\\{").containsMatchIn(t) ->
                listOfNotNull(parseCooklang(text, fileName.substringBeforeLast('.')))
            t.lineSequence().firstOrNull()?.count { it == ',' }?.let { it >= 3 } == true -> parseCsv(text)
            else -> parseText(text)
        }
    }

    private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    fun parseZip(bytes: ByteArray): List<Recipe> {
        val out = mutableListOf<Recipe>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.startsWith(".") || entry.name.startsWith("__MACOSX")) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("json", "jsonld", "csv", "cook", "cooklang", "txt", "md")) continue
                val data = zip.readBytes()
                out += runCatching { parse(name, data) }.getOrDefault(emptyList())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ JSON

    fun parseJson(text: String): List<Recipe> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val nodes = if (root is JsonArray) root.toList() else listOf(root)
        return nodes.flatMap { node -> parseJsonNode(node) }.filter { it.title.isNotBlank() || it.ingredients.isNotEmpty() }
    }

    private fun parseJsonNode(node: JsonElement): List<Recipe> {
        val obj = node as? JsonObject
        // Our own export carries the full recipe; prefer it for a lossless round trip.
        obj?.get("recipeBox")?.let { box ->
            runCatching { json.decodeFromJsonElement(Recipe.serializer(), box) }.getOrNull()?.let { return listOf(it.copy(id = 0)) }
        }
        val schema = JsonLdRecipeParser.parseAll(node)
        if (schema.isNotEmpty()) return schema
        // A bare recipe object in this app's native shape.
        if (obj != null && "title" in obj && "ingredients" in obj) {
            runCatching { json.decodeFromJsonElement(Recipe.serializer(), obj) }.getOrNull()?.let { return listOf(it.copy(id = 0)) }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------- CSV

    /** RFC 4180 reader: quoted cells may contain commas, quotes ("") and newlines. */
    fun readCsv(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == delimiter -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            if (row.any { it.isNotBlank() }) rows += row
        }
        return rows
    }

    fun parseCsv(text: String): List<Recipe> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
        val rows = readCsv(text, delimiter)
        if (rows.size < 2) return emptyList()
        val header = rows.first().map { h -> h.lowercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^a-z]"), "") }
        fun col(vararg names: String): Int = header.indexOfFirst { h -> names.any { h == it } }.let { exact ->
            if (exact >= 0) exact else header.indexOfFirst { h -> names.any { h.startsWith(it) } }
        }
        val iTitle = col("title", "name", "recipe", "recipename")
        val iDesc = col("description", "summary")
        val iCat = col("category", "course", "categories")
        val iTags = col("tags", "keywords", "tag")
        val iServ = col("servings", "yield", "serves", "recipeyield")
        val iPrep = col("preptime", "prep")
        val iCook = col("cooktime", "cook")
        val iTotal = col("totaltime", "total", "time")
        val iIng = col("ingredients", "ingredient", "recipeingredient")
        val iSteps = col("steps", "instructions", "directions", "method", "recipeinstructions")
        val iCal = col("calories", "kcal", "energy")
        val iProt = col("protein")
        val iCarb = col("carbs", "carbohydrates", "carbohydrate")
        val iFat = col("fat")
        val iFib = col("fiber", "fibre")
        val iSug = col("sugar", "sugars")
        val iSod = col("sodium", "salt")
        val iNotes = col("notes", "note", "comments")
        val iAuthor = col("author", "creator")
        val iSrc = col("source", "url", "link", "sourceurl")
        val iFav = col("favorite", "favourite", "starred")
        if (iTitle < 0 && iIng < 0) return emptyList()

        return rows.drop(1).mapNotNull { r ->
            fun v(i: Int) = r.getOrNull(i)?.trim().orEmpty()
            fun d(i: Int) = Regex("\\d+(?:[.,]\\d+)?").find(v(i))?.value?.replace(',', '.')?.toDoubleOrNull()
            val title = v(iTitle)
            val ingredients = splitLines(v(iIng)).map { IngredientParser.parse(it) }.filter { it.name.isNotBlank() }
            if (title.isBlank() && ingredients.isEmpty()) return@mapNotNull null
            val prep = minutes(v(iPrep))
            val cook = minutes(v(iCook)).takeIf { it > 0 } ?: (minutes(v(iTotal)) - prep).coerceAtLeast(0)
            val source = v(iSrc)
            Recipe(
                title = title.ifBlank { "Imported recipe" },
                description = v(iDesc),
                category = v(iCat).split(',', ';').first().trim(),
                tags = v(iTags).split(',', ';').map(::normalizeTag).filter { it.isNotBlank() }.distinct(),
                servings = d(iServ)?.toInt()?.coerceIn(1, 100) ?: 2,
                prepTimeMinutes = prep,
                cookTimeMinutes = cook,
                ingredients = ingredients,
                steps = splitLines(v(iSteps)).map(::stripNumbering).filter { it.isNotBlank() },
                nutrition = Nutrition(d(iCal), d(iProt), d(iCarb), d(iFat), d(iFib), d(iSug), d(iSod)),
                notes = v(iNotes),
                author = v(iAuthor),
                sourceUrl = source,
                sourcePlatform = if (source.startsWith("http")) LinkResolver.platformOf(source) else SourcePlatform.MANUAL,
                favorite = v(iFav).lowercase() in setOf("yes", "true", "1", "y", "★"),
            )
        }
    }

    // ------------------------------------------------------------------- TXT

    private enum class Section { NONE, INGREDIENTS, STEPS, NUTRITION, NOTES }

    private val headings = listOf(
        Section.INGREDIENTS to Regex("^(ingredients?|you will need|what you need)\\b.*$", RegexOption.IGNORE_CASE),
        Section.STEPS to Regex("^(steps?|instructions?|directions?|method|preparation)\\b.*$", RegexOption.IGNORE_CASE),
        Section.NUTRITION to Regex("^nutrition\\b.*$", RegexOption.IGNORE_CASE),
        Section.NOTES to Regex("^(notes?|tips?)\\b:?$", RegexOption.IGNORE_CASE),
    )

    /**
     * Plain text such as this app's TXT export or text pulled out of a PDF: a title line,
     * optional meta lines, then INGREDIENTS / STEPS / NUTRITION / NOTES sections. Several recipes
     * separated by a line of '=' are supported. Returns nothing if no ingredient section exists
     * (the caller can then ask Gemini to make sense of it).
     */
    fun parseText(text: String): List<Recipe> =
        text.split(Regex("\\n\\s*={5,}\\s*\\n")).mapNotNull { parseTextRecipe(it) }

    fun parseTextRecipe(text: String): Recipe? {
        val lines = text.lines().map { it.trim() }
        if (lines.none { line -> headings.first().second.matches(line.trimEnd(':')) }) return null
        var title = ""
        val description = StringBuilder()
        var servings = 2
        var prep = 0
        var cook = 0
        var category = ""
        var tags = emptyList<String>()
        var source = ""
        var author = ""
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val nutritionText = StringBuilder()
        val notes = StringBuilder()
        var section = Section.NONE

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || Regex("^[-=_]{3,}$").matches(line)) continue
            val heading = headings.firstOrNull { line.length < 40 && it.second.matches(line.trimEnd(':')) }
            if (heading != null) {
                section = heading.first
                continue
            }

            if (line.startsWith("Source:", ignoreCase = true)) {
                val rest = line.substringAfter(':').trim()
                source = rest.substringBefore(" (").trim()
                author = Regex("\\((.*)\\)$").find(rest)?.groupValues?.get(1).orEmpty()
                continue
            }
            when (section) {
                Section.NONE -> when {
                    title.isEmpty() -> title = line
                    line.startsWith("Serves", true) || line.contains("Prep ", true) && line.contains("Cook ", true) -> {
                        Regex("Serves\\s+(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(line)?.let {
                            servings = it.groupValues[1].replace(',', '.').toDouble().toInt().coerceAtLeast(1)
                        }
                        Regex("Prep\\s+([^·|]+)", RegexOption.IGNORE_CASE).find(line)?.let { prep = minutes(it.groupValues[1]) }
                        Regex("Cook\\s+([^·|]+)", RegexOption.IGNORE_CASE).find(line)?.let { cook = minutes(it.groupValues[1]) }
                    }
                    line.startsWith("Category:", true) -> category = line.substringAfter(':').trim()
                    line.startsWith("Tags:", true) -> tags = line.substringAfter(':').split(',', ' ').map(::normalizeTag).filter { it.isNotBlank() }
                    // Our PDF puts "Category  ·  #tag  ·  #tag" on one line.
                    line.contains("#") && line.split("·").all { it.isNotBlank() } && line.split("·").count { it.trim().startsWith("#") } >= 1 -> {
                        val parts = line.split("·").map { it.trim() }
                        tags = parts.filter { it.startsWith("#") }.map(::normalizeTag)
                        parts.firstOrNull { !it.startsWith("#") }?.let { category = it }
                    }
                    else -> description.appendLine(line)
                }
                Section.INGREDIENTS -> ingredients += line
                Section.STEPS -> steps += line
                Section.NUTRITION -> nutritionText.appendLine(line)
                Section.NOTES -> notes.appendLine(line)
            }
        }
        if (title.isBlank() && ingredients.isEmpty()) return null
        return Recipe(
            title = title.ifBlank { "Imported recipe" },
            description = description.toString().trim(),
            servings = servings,
            prepTimeMinutes = prep,
            cookTimeMinutes = cook,
            category = category,
            tags = tags.distinct(),
            ingredients = joinWrapped(ingredients, BULLET).map { IngredientParser.parse(stripBullet(it)) }.filter { it.name.isNotBlank() },
            steps = joinWrapped(steps, NUMBERED).map { stripNumbering(stripBullet(it)) }.filter { it.isNotBlank() },
            nutrition = parseNutritionText(nutritionText.toString()),
            notes = notes.toString().trim(),
            sourceUrl = source,
            author = author,
            sourcePlatform = if (source.startsWith("http")) LinkResolver.platformOf(source) else SourcePlatform.MANUAL,
        )
    }

    /** Reads "Calories: 320 kcal · Protein: 9 g" style text (any separators / one per line). */
    fun parseNutritionText(text: String): Nutrition {
        fun find(vararg names: String): Double? = names.firstNotNullOfOrNull { n ->
            Regex("$n\\w*\\s*:?\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        }
        return Nutrition(
            calories = find("calories", "energy"),
            proteinGrams = find("protein"),
            carbsGrams = find("carbohydrate", "carbs"),
            fatGrams = find("fat"),
            fiberGrams = find("fiber", "fibre"),
            sugarGrams = find("sugar"),
            sodiumMg = find("sodium"),
        )
    }

    // -------------------------------------------------------------- Cooklang

    private val INGREDIENT = Regex("@(?:([^@#~{}\\n]+?)\\{([^}\\n]*)\\}|([^\\s@#~{}.,;:!?()]+))(?:\\(([^)\\n]*)\\))?")
    private val COOKWARE = Regex("#(?:([^@#~{}\\n]+?)\\{([^}\\n]*)\\}|([^\\s@#~{}.,;:!?()]+))")
    private val TIMER = Regex("~([^@#~{}\\n]*)\\{([^}\\n]*)\\}")

    fun parseCooklang(text: String, fallbackTitle: String = ""): Recipe? {
        var body = text.replace("\r\n", "\n")
        val meta = linkedMapOf<String, String>()

        // YAML front matter (current spec)
        Regex("^---\\n(.*?)\\n---\\n?", RegexOption.DOT_MATCHES_ALL).find(body)?.let { m ->
            m.groupValues[1].lines().forEach { line ->
                val k = line.substringBefore(':', "").trim().lowercase()
                if (k.isNotEmpty()) meta[k] = unquote(line.substringAfter(':').trim())
            }
            body = body.substring(m.range.last + 1)
        }
        // Legacy ">> key: value" metadata
        body = body.lines().filter { line ->
            val legacy = Regex("^>>\\s*([^:]+):\\s*(.*)$").find(line.trim())
            if (legacy != null) meta[legacy.groupValues[1].trim().lowercase()] = legacy.groupValues[2].trim()
            legacy == null
        }.joinToString("\n")

        // Comments: block [- -] and line "--". Collect line comments as notes.
        body = body.replace(Regex("\\[-.*?-]", RegexOption.DOT_MATCHES_ALL), "")
        val comments = mutableListOf<String>()
        body = body.lines().joinToString("\n") { line ->
            val idx = line.indexOf("--")
            if (idx >= 0) {
                comments += line.substring(idx + 2).trim()
                line.substring(0, idx)
            } else line
        }

        val ingredients = mutableListOf<Ingredient>()
        val steps = body.split(Regex("\\n\\s*\\n")).map { it.replace("\n", " ").trim() }
            .filter { it.isNotBlank() && !it.startsWith("=") && !it.startsWith(">") }
            .map { paragraph ->
                var step = INGREDIENT.replace(paragraph) { m ->
                    val name = (m.groupValues[1].ifBlank { m.groupValues[3] }).trim()
                    val (qty, unit) = amount(m.groupValues[2])
                    ingredients += Ingredient(qty, unit, name, m.groupValues[4].trim())
                    name
                }
                step = COOKWARE.replace(step) { m -> m.groupValues[1].ifBlank { m.groupValues[3] }.trim() }
                step = TIMER.replace(step) { m ->
                    val (q, u) = m.groupValues[2].split('%', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                    listOf(q, u).filter { it.isNotBlank() }.joinToString(" ").ifBlank { m.groupValues[1].trim() }
                }
                step.replace(Regex("\\s+"), " ").trim()
            }
            .toMutableList()

        // Drop the "Gather …" step our exporter adds for ingredients no step mentions.
        if (steps.firstOrNull()?.startsWith("Gather ") == true && text.contains(Regex("\\n\\s*Gather @"))) steps.removeAt(0)
        if (steps.isEmpty() && ingredients.isEmpty()) return null

        val nutritionComments = comments.filter { Regex("^(calories|protein|carbohydrates|fat|fiber|sugar|sodium):", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val noteComments = comments.filter { it.isNotBlank() && it !in nutritionComments && !it.startsWith("Nutrition per serving", true) }
        val source = meta["source"] ?: meta["source.url"] ?: meta["url"] ?: ""

        return Recipe(
            title = (meta["title"] ?: fallbackTitle).ifBlank { "Imported recipe" },
            description = meta["description"].orEmpty(),
            servings = Regex("\\d+").find(meta["servings"] ?: meta["serves"] ?: meta["yield"] ?: "")?.value?.toIntOrNull()?.coerceIn(1, 100) ?: 2,
            prepTimeMinutes = minutes(meta["prep time"] ?: meta["time.prep"] ?: meta["prep_time"] ?: ""),
            cookTimeMinutes = minutes(meta["cook time"] ?: meta["time.cook"] ?: meta["cook_time"] ?: ""),
            category = meta["course"] ?: meta["category"] ?: "",
            tags = (meta["tags"] ?: "").removePrefix("[").removeSuffix("]").split(',')
                .map { normalizeTag(unquote(it.trim())) }.filter { it.isNotBlank() },
            ingredients = ingredients,
            steps = steps,
            nutrition = parseNutritionText(nutritionComments.joinToString("\n")),
            notes = noteComments.joinToString("\n"),
            author = meta["author"].orEmpty(),
            sourceUrl = source,
            imageUrl = meta["image"].orEmpty(),
            sourcePlatform = if (source.startsWith("http")) LinkResolver.platformOf(source) else SourcePlatform.MANUAL,
        )
    }

    private fun amount(raw: String): Pair<Double?, String> {
        if (raw.isBlank()) return null to ""
        val qty = raw.substringBefore('%').trim()
        val unit = if ('%' in raw) raw.substringAfter('%').trim() else ""
        return Quantities.parse(qty) to unit
    }

    // ---------------------------------------------------------------- shared

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'").replace("\\\"", "\"")

    private fun splitLines(cell: String) = cell.split('\n', '\r').map { stripBullet(it.trim()) }.filter { it.isNotBlank() }

    private val BULLET = Regex("^[-•*▪◦]\\s*")
    private val NUMBERED = Regex("^(\\d+[.)]|step\\s*\\d+:?|[-•*▪◦])\\s*", RegexOption.IGNORE_CASE)

    /**
     * When a list uses bullets/numbers, lines without one are wrapped continuations (common in
     * text extracted from PDFs) and are joined to the previous item. Otherwise every line is an item.
     */
    private fun joinWrapped(lines: List<String>, marker: Regex): List<String> {
        if (lines.none { marker.containsMatchIn(it) }) return lines
        val out = mutableListOf<String>()
        lines.forEach { line ->
            if (marker.containsMatchIn(line) || out.isEmpty()) out += line else out[out.lastIndex] = out.last() + " " + line
        }
        return out
    }

    private fun stripBullet(line: String) = line.replace(Regex("^[-•*▪◦]\\s*"), "").trim()

    private fun stripNumbering(line: String) =
        line.replace(Regex("^(step\\s*)?\\d+[.):-]?\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** "1 h 15 min", "75 minutes", "PT1H15M", "1:15", "90" -> minutes. */
    fun minutes(text: String): Int {
        val t = text.trim()
        if (t.isEmpty() || t == "—" || t == "-") return 0
        if (t.startsWith("P", ignoreCase = true)) return JsonLdRecipeParser.isoMinutes(t)
        Regex("^(\\d+):(\\d{2})$").find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val h = Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val m = Regex("(\\d+)\\s*(m|min|mins|minute|minutes)\\b", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (h == 0.0 && m == 0) return t.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
        return (h * 60).toInt() + m
    }
}
