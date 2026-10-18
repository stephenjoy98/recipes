package com.stephenjoy.recipes.export

import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeParsersTest {

    private val recipe = Recipe(
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
        steps = listOf("Whisk the flour, eggs and salt.", "Heat the olive oil and fry until golden."),
        nutrition = Nutrition(calories = 320.0, proteinGrams = 9.0, sodiumMg = 400.0),
        notes = "Great with berries.",
        sourceUrl = "https://www.instagram.com/reel/abc/",
        author = "chef",
    )

    private fun assertCore(r: Recipe) {
        assertEquals(recipe.title, r.title)
        assertEquals(4, r.servings)
        assertEquals(10, r.prepTimeMinutes)
        assertEquals(75, r.cookTimeMinutes)
        assertEquals("Breakfast", r.category)
        assertEquals(listOf("quick", "sweet"), r.tags)
        assertEquals(5, r.ingredients.size)
        assertEquals(Ingredient(1.5, "cup", "flour", "sifted"), r.ingredients[0])
        assertEquals(3.0, r.ingredients[1].quantity!!, 1e-9)
        assertEquals("olive oil", r.ingredients[2].name)
        assertNull(r.ingredients[3].quantity)
        assertEquals(recipe.steps, r.steps)
        assertEquals(320.0, r.nutrition.calories!!, 1e-9)
        assertEquals(400.0, r.nutrition.sodiumMg!!, 1e-9)
    }

    @Test
    fun `json round trip is lossless`() {
        val back = RecipeParsers.parse("x.json", RecipeFormats.toJson(listOf(recipe, recipe.copy(uid = "u2", title = "B"))).toByteArray())
        assertEquals(2, back.size)
        assertEquals(recipe, back[0])
        assertEquals("B", back[1].title)
    }

    @Test
    fun `csv round trip`() {
        val back = RecipeParsers.parse("x.csv", RecipeFormats.toCsv(listOf(recipe, recipe.copy(title = "B"))).toByteArray())
        assertEquals(2, back.size)
        assertCore(back[0])
        assertEquals(recipe.description, back[0].description)
        assertEquals(recipe.sourceUrl, back[0].sourceUrl)
        assertEquals("Great with berries.", back[0].notes)
    }

    @Test
    fun `text round trip`() {
        val back = RecipeParsers.parse("x.txt", RecipeFormats.toText(listOf(recipe, recipe.copy(title = "B"))).toByteArray())
        assertEquals(2, back.size)
        assertCore(back[0])
        assertEquals("Great with berries.", back[0].notes)
        assertEquals(recipe.sourceUrl, back[0].sourceUrl)
        assertEquals("chef", back[0].author)
    }

    @Test
    fun `cooklang round trip`() {
        val back = RecipeParsers.parse("Fluffy Pancakes.cook", RecipeFormats.toCooklang(recipe).toByteArray())
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals(recipe.title, r.title)
        assertEquals(4, r.servings)
        assertEquals(75, r.cookTimeMinutes)
        assertEquals(listOf("quick", "sweet"), r.tags)
        assertEquals(recipe.ingredients.map { it.name }.toSet(), r.ingredients.map { it.name }.toSet())
        assertEquals(Ingredient(1.0, "tsp", "vanilla"), r.ingredients.first { it.name == "vanilla" })
        assertEquals(recipe.steps, r.steps)
        assertEquals(320.0, r.nutrition.calories!!, 1e-9)
        assertEquals("Great with berries.", r.notes)
    }

    @Test
    fun `zip of cook files`() {
        val zip = RecipeFormats.zip(listOf("A.cook" to RecipeFormats.toCooklang(recipe).toByteArray(), "B.cook" to RecipeFormats.toCooklang(recipe.copy(title = "B")).toByteArray()))
        assertEquals(listOf("Fluffy Pancakes", "B"), RecipeParsers.parse("export.zip", zip).map { it.title })
    }

    @Test
    fun `cooklang written by hand`() {
        val cook = """
            >> servings: 2
            >> source: https://cooklang.org

            Crack @eggs{3} into a #bowl{}, add @milk{250%ml} and a pinch of @salt.

            -- don't overmix
            Fry in a #frying pan{} for ~{3%minutes}.
        """.trimIndent()
        val r = RecipeParsers.parseCooklang(cook, "Omelette")!!
        assertEquals("Omelette", r.title)
        assertEquals(2, r.servings)
        assertEquals(listOf(Ingredient(3.0, "", "eggs"), Ingredient(250.0, "ml", "milk"), Ingredient(null, "", "salt")), r.ingredients)
        assertEquals("Crack eggs into a bowl, add milk and a pinch of salt.", r.steps[0])
        assertEquals("Fry in a frying pan for 3 minutes.", r.steps[1])
        assertEquals("don't overmix", r.notes)
    }

    @Test
    fun `json from other apps`() {
        val mealieLike = """[{"@context":"https://schema.org","@type":"Recipe","name":"Soup","recipeYield":"6",
            "recipeIngredient":["1 onion","2 cups stock"],"recipeInstructions":"Chop.\nSimmer.","totalTime":"PT30M"},
            {"@type":"Recipe","name":"Salad","recipeIngredient":["1 lettuce"]}]"""
        val r = RecipeParsers.parse("export.json", mealieLike.toByteArray())
        assertEquals(listOf("Soup", "Salad"), r.map { it.title })
        assertEquals(6, r[0].servings)
        assertEquals(listOf("Chop.", "Simmer."), r[0].steps)
    }

    @Test
    fun `csv from a spreadsheet with different headers`() {
        val csv = "Name;Yield;Total Time;Ingredients;Directions\n" +
            "Toast;1;5 min;\"1 slice bread\n1 tbsp butter\";\"1) Toast the bread\n2) Butter it\"\n"
        val r = RecipeParsers.parse("sheet.csv", csv.toByteArray()).single()
        assertEquals("Toast", r.title)
        assertEquals(5, r.cookTimeMinutes)
        assertEquals(Ingredient(1.0, "slice", "bread"), r.ingredients[0])
        assertEquals(listOf("Toast the bread", "Butter it"), r.steps)
    }

    @Test
    fun `pdf style text with wrapped lines`() {
        val text = """
            Garlic Pasta
            Serves 2 · Prep 5 min · Cook 12 min · Total 17 min
            Dinner  ·  #italian  ·  #quick
            Ingredients
            •  200 g spaghetti
            •  3 garlic cloves, sliced very thinly so they turn golden
            and crisp
            Steps
            1.  Boil the spaghetti in plenty of well salted water until it is
            al dente.
            2.  Fry the garlic.
            Nutrition per serving (estimate)
            Calories: 450 kcal   ·   Protein: 14 g
        """.trimIndent()
        val r = RecipeParsers.parseText(text).single()
        assertEquals("Garlic Pasta", r.title)
        assertEquals("Dinner", r.category)
        assertEquals(listOf("italian", "quick"), r.tags)
        assertEquals(17 - 5, r.cookTimeMinutes)
        assertEquals(2, r.ingredients.size)
        assertTrue(r.ingredients[1].note.endsWith("golden and crisp"))
        assertEquals("Boil the spaghetti in plenty of well salted water until it is al dente.", r.steps[0])
        assertEquals(450.0, r.nutrition.calories!!, 1e-9)
        assertEquals(14.0, r.nutrition.proteinGrams!!, 1e-9)
    }

    @Test
    fun `unstructured text is left for gemini`() {
        assertTrue(RecipeParsers.parseText("Mix some flour and water, bake it. Yum!").isEmpty())
    }

    @Test
    fun `durations`() {
        assertEquals(75, RecipeParsers.minutes("1 h 15 min"))
        assertEquals(90, RecipeParsers.minutes("1.5 hours"))
        assertEquals(75, RecipeParsers.minutes("1:15"))
        assertEquals(20, RecipeParsers.minutes("20 minutes"))
        assertEquals(0, RecipeParsers.minutes("—"))
    }
}
