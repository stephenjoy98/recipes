package com.stephenjoy.recipes.export

import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.extract.JsonLdRecipeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class RecipeFormatsTest {

    private val pancakes = Recipe(
        uid = "u1",
        title = "Fluffy Pancakes",
        description = "Quick, \"diner\" style.",
        servings = 4,
        prepTimeMinutes = 10,
        cookTimeMinutes = 75,
        category = "Breakfast",
        tags = listOf("quick", "sweet"),
        ingredients = listOf(
            Ingredient(1.5, "cup", "flour", "sifted"),
            Ingredient(3.0, "", "eggs"),
            Ingredient(2.0, "tbsp", "olive oil"),
            Ingredient(null, "", "salt", "to taste"),
            Ingredient(1.0, "tsp", "vanilla"),
        ),
        steps = listOf("Whisk the flour, eggs and salt.", "Heat the olive oil and fry, about 2 min @ medium heat."),
        nutrition = Nutrition(calories = 320.4, proteinGrams = 9.25, sodiumMg = 400.0),
        notes = "Great with berries.\nFreeze leftovers.",
        sourceUrl = "https://youtube.com/shorts/abc",
    )

    @Test
    fun `json is schema org and parses back`() {
        val text = RecipeFormats.toJson(listOf(pancakes))
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("Recipe", o["@type"]!!.jsonPrimitive.content)
        assertEquals("PT1H15M", o["cookTime"]!!.jsonPrimitive.content)
        assertEquals("PT1H25M", o["totalTime"]!!.jsonPrimitive.content)
        // Our own schema.org importer reads it back losslessly enough.
        val back = JsonLdRecipeParser.parse(listOf(text))!!
        assertEquals(pancakes.title, back.title)
        assertEquals(4, back.servings)
        assertEquals(75, back.cookTimeMinutes)
        assertEquals(5, back.ingredients.size)
        assertEquals(1.5, back.ingredients[0].quantity!!, 1e-9)
        assertEquals(pancakes.steps, back.steps)
        assertEquals(320.0, back.nutrition.calories!!, 1e-9)

        val many = Json.parseToJsonElement(RecipeFormats.toJson(listOf(pancakes, pancakes.copy(title = "B"))))
        assertTrue(many is JsonArray)
        assertEquals(2, (many as JsonArray).size)
    }

    @Test
    fun `csv quotes cells with commas quotes and newlines`() {
        val csv = RecipeFormats.toCsv(listOf(pancakes))
        assertTrue(csv.startsWith("﻿Title,Description,"))
        assertTrue(csv.contains("\"Quick, \"\"diner\"\" style.\""))
        assertTrue(csv.contains("\"1½ cup flour, sifted\n3 eggs\n"))
        assertEquals(2, csv.split("\r\n").filter { it.isNotEmpty() }.size.coerceAtMost(2))
    }

    @Test
    fun `text export`() {
        val txt = RecipeFormats.toText(listOf(pancakes))
        assertTrue(txt.contains("• 1½ cup flour, sifted"))
        assertTrue(txt.contains("2. Heat the olive oil"))
        assertTrue(txt.contains("Serves 4 · Prep 10 min · Cook 1 h 15 min"))
    }

    @Test
    fun `cooklang marks up every ingredient once`() {
        val cook = RecipeFormats.toCooklang(pancakes)
        assertTrue(cook.startsWith("---\ntitle: \"Fluffy Pancakes\"\n"))
        assertTrue(cook.contains("servings: 4"))
        assertTrue(cook.contains("tags: [\"quick\", \"sweet\"]"))
        assertTrue(cook.contains("@flour{1.5%cup}(sifted)"))
        assertTrue(cook.contains("@eggs{3}"))
        assertTrue(cook.contains("@salt{}(to taste)"))
        assertTrue(cook.contains("@olive oil{2%tbsp}"))
        // Not mentioned in any step -> gathered up front
        assertTrue(cook.contains("Gather @vanilla{1%tsp}."))
        // A literal '@' in the text must not start an ingredient
        assertFalse(cook.contains("@ medium"))
        assertTrue(cook.contains("-- Great with berries."))
        // each ingredient tagged exactly once
        assertEquals(1, Regex("@flour\\{").findAll(cook).count())
    }

    @Test
    fun `zip dedupes names`() {
        val bytes = RecipeFormats.zip(listOf("Soup.cook" to "a".toByteArray(), "Soup.cook" to "b".toByteArray()))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; names += e.name }
        }
        assertEquals(listOf("Soup.cook", "Soup (2).cook"), names)
        assertEquals("a b c", RecipeFormats.fileName("a/b:c"))
    }
}
