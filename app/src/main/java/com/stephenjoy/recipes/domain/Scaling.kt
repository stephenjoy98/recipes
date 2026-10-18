package com.stephenjoy.recipes.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Ratio-based scaling. Every ingredient is multiplied by the same factor, so changing one
 * ingredient (3 eggs -> 4 eggs, factor 4/3) keeps the whole recipe in proportion.
 */
object Scaling {

    fun scale(ingredients: List<Ingredient>, factor: Double): List<Ingredient> =
        if (factor == 1.0) ingredients
        else ingredients.map { ing -> ing.quantity?.let { ing.copy(quantity = it * factor) } ?: ing }

    /** Factor that turns [original]'s amount into [newQuantity]; null if it can't be scaled. */
    fun factorFor(original: Ingredient, newQuantity: Double): Double? {
        val base = original.quantity ?: return null
        if (base <= 0.0 || newQuantity <= 0.0) return null
        return newQuantity / base
    }

    fun factorForServings(baseServings: Int, newServings: Int): Double =
        if (baseServings <= 0 || newServings <= 0) 1.0 else newServings.toDouble() / baseServings

    /** Servings implied by a factor, e.g. 4 servings x1.5 = 6. Rounded to a whole serving. */
    fun scaledServings(baseServings: Int, factor: Double): Int =
        (baseServings * factor).roundToInt().coerceAtLeast(1)

    fun formatFactor(factor: Double): String {
        val rounded = (factor * 100).roundToInt() / 100.0
        return "×" + trimZeros(rounded)
    }
}

object Quantities {

    private val fractions = listOf(
        1.0 / 8 to "⅛", 1.0 / 4 to "¼", 1.0 / 3 to "⅓", 3.0 / 8 to "⅜", 1.0 / 2 to "½",
        5.0 / 8 to "⅝", 2.0 / 3 to "⅔", 3.0 / 4 to "¾", 7.0 / 8 to "⅞",
    )

    private val unicodeFractions = mapOf(
        '½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )

    /** Units measured by weight/volume where whole numbers read better than fractions. */
    private val metricUnits = setOf("g", "gram", "grams", "kg", "ml", "millilitre", "milliliter", "l", "mg")

    /**
     * Human friendly quantity: kitchen fractions for cups/spoons/counts (1½, ⅓),
     * whole numbers for large metric amounts (250 g), and trimmed decimals otherwise.
     */
    fun format(quantity: Double, unit: String = ""): String {
        if (quantity <= 0.0) return "0"
        val u = unit.trim().lowercase()
        if (u in metricUnits) {
            return when {
                quantity >= 10 -> quantity.roundToInt().toString()
                else -> trimZeros((quantity * 10).roundToInt() / 10.0)
            }
        }
        val whole = floor(quantity)
        val frac = quantity - whole
        if (frac < 0.06) return trimZeros(whole)
        if (frac > 0.94) return trimZeros(whole + 1)
        val match = fractions.minByOrNull { abs(it.first - frac) }!!
        if (abs(match.first - frac) <= 0.02) {
            return if (whole == 0.0) match.second else "${whole.toInt()}${match.second}"
        }
        return trimZeros((quantity * 100).roundToInt() / 100.0)
    }

    /**
     * Parses "2", "1.5", "1,5", "1 1/2", "3/4", "1½", "½", "1-2" (takes the first value).
     * Returns null when the text has no leading number.
     */
    fun parse(text: String): Double? = parseLeading(text)?.first

    /** Parses a leading quantity and returns it with the number of characters consumed. */
    internal fun parseLeading(text: String): Pair<Double, Int>? {
        val m = LEADING.find(text) ?: return null
        val raw = m.value
        val value = parseToken(raw.trim()) ?: return null
        return value to m.range.last + 1
    }

    private fun parseToken(token: String): Double? {
        // Range like "1-2" or "1 to 2": use the lower bound.
        val range = token.split(Regex("\\s*(?:-|–|to)\\s*")).filter { it.isNotBlank() }
        if (range.size > 1) return parseToken(range.first())
        var total = 0.0
        var any = false
        for (part in token.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val v = parseSimple(part) ?: return if (any) total else null
            total += v
            any = true
        }
        return if (any) total else null
    }

    private fun parseSimple(part: String): Double? {
        if (part.length == 1 && part[0] in unicodeFractions) return unicodeFractions[part[0]]
        val last = part.last()
        if (last in unicodeFractions && part.length > 1) {
            val w = part.dropLast(1).toDoubleOrNull() ?: return null
            return w + unicodeFractions.getValue(last)
        }
        if ('/' in part) {
            val (n, d) = part.split('/', limit = 2)
            val num = n.toDoubleOrNull() ?: return null
            val den = d.toDoubleOrNull() ?: return null
            return if (den == 0.0) null else num / den
        }
        return part.replace(',', '.').toDoubleOrNull()
    }

    private const val UF = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private const val NUM = "(?:\\d+/\\d+|\\d+(?:[.,]\\d+)?[$UF]?|[$UF])"
    private val LEADING = Regex(
        "^\\s*$NUM(?:\\s+\\d+/\\d+)?(?:\\s*(?:-|–|to)\\s*$NUM(?:\\s+\\d+/\\d+)?)?",
    )
}

/** Turns a free-text ingredient line ("2 1/2 cups flour, sifted") into an [Ingredient]. */
object IngredientParser {

    private val units: Map<String, String> = buildMap {
        fun add(canonical: String, vararg aliases: String) {
            put(canonical, canonical)
            aliases.forEach { put(it, canonical) }
        }
        add("cup", "cups", "c")
        add("tbsp", "tablespoon", "tablespoons", "tbs", "tbl", "tbsps", "T")
        add("tsp", "teaspoon", "teaspoons", "tsps", "t")
        add("g", "gram", "grams", "gr", "gms")
        add("kg", "kilogram", "kilograms", "kgs")
        add("mg", "milligram", "milligrams")
        add("ml", "milliliter", "milliliters", "millilitre", "millilitres", "mls")
        add("l", "liter", "liters", "litre", "litres")
        add("oz", "ounce", "ounces")
        add("fl oz", "fluid ounce", "fluid ounces")
        add("lb", "lbs", "pound", "pounds")
        add("pinch", "pinches")
        add("dash", "dashes")
        add("clove", "cloves")
        add("can", "cans")
        add("slice", "slices")
        add("stick", "sticks")
        add("handful", "handfuls")
        add("bunch", "bunches")
        add("sprig", "sprigs")
        add("piece", "pieces", "pcs", "pc")
        add("pack", "packs", "packet", "packets")
        add("quart", "quarts", "qt")
        add("pint", "pints", "pt")
    }

    fun parse(line: String): Ingredient {
        val clean = line.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim()
        if (clean.isEmpty()) return Ingredient()
        val leading = Quantities.parseLeading(clean)
        var rest = if (leading != null) clean.substring(leading.second).trim() else clean
        var unit = ""
        // Units can be one or two words ("fl oz", "fluid ounces"); also strip a trailing '.'
        for (words in 2 downTo 1) {
            val candidate = rest.split(Regex("\\s+")).take(words).joinToString(" ")
            val key = candidate.trimEnd('.')
            val canonical = units[key] ?: units[key.lowercase()]?.takeIf { key.length > 1 }
            if (canonical != null && rest.split(Regex("\\s+")).size > words) {
                unit = canonical
                rest = rest.substring(candidate.length).trim().removePrefix("of ").trim()
                break
            }
        }
        val (name, note) = splitNote(rest)
        return Ingredient(quantity = leading?.first, unit = unit, name = name, note = note)
    }

    private fun splitNote(text: String): Pair<String, String> {
        val paren = Regex("\\(([^)]*)\\)").find(text)
        val comma = text.indexOf(',')
        return when {
            comma > 0 -> text.substring(0, comma).trim() to text.substring(comma + 1).trim()
            paren != null -> text.removeRange(paren.range).trim().replace(Regex("\\s+"), " ") to
                paren.groupValues[1].trim()
            else -> text to ""
        }
    }
}

/** Display line for an ingredient, e.g. "1½ cup flour". */
fun Ingredient.displayAmount(): String {
    val q = quantity?.let { Quantities.format(it, unit) } ?: ""
    return listOf(q, unit).filter { it.isNotBlank() }.joinToString(" ")
}

internal fun trimZeros(value: Double): String {
    val s = if (value == floor(value)) value.toLong().toString() else value.toString()
    return s
}
