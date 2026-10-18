package com.stephenjoy.recipes.extract

import com.stephenjoy.recipes.ai.RecipePrompts
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.SourcePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun `detects platforms and ids`() {
        assertEquals(SourcePlatform.YOUTUBE_SHORTS, LinkResolver.platformOf("https://youtube.com/shorts/abcDEF12345?si=x"))
        assertEquals(SourcePlatform.YOUTUBE, LinkResolver.platformOf("https://youtu.be/abcDEF12345"))
        assertEquals(SourcePlatform.INSTAGRAM, LinkResolver.platformOf("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=1"))
        assertEquals(SourcePlatform.TIKTOK, LinkResolver.platformOf("https://vm.tiktok.com/ZM123/"))
        assertEquals(SourcePlatform.WEB, LinkResolver.platformOf("https://www.bbcgoodfood.com/recipes/x"))
        assertEquals("abcDEF12345", LinkResolver.youtubeVideoId("https://youtube.com/shorts/abcDEF12345?si=x"))
        assertEquals("abcDEF12345", LinkResolver.youtubeVideoId("https://www.youtube.com/watch?v=abcDEF12345&t=3"))
        assertEquals("abcDEF12345", LinkResolver.youtubeVideoId("https://youtu.be/abcDEF12345"))
        assertEquals("C1a2B3c4D5e", LinkResolver.instagramShortcode("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=1"))
        assertEquals("C1a2B3c4D5e", LinkResolver.instagramShortcode("https://www.instagram.com/chef/p/C1a2B3c4D5e/"))
    }

    @Test
    fun `extracts url from shared text`() {
        assertEquals(
            "https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=MW",
            LinkResolver.extractUrl("Look at this! https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=MW."),
        )
        assertNull(LinkResolver.extractUrl("no link here"))
    }

    @Test
    fun `parses fenced model answer`() {
        val answer = """
            Sure! Here it is:
            ```json
            {"title":"Fluffy Pancakes","description":"Quick pancakes.","servings":"4 servings","prepTimeMinutes":10,
             "cookTimeMinutes":"15","category":"breakfast","tags":["#Breakfast","Quick"],
             "ingredients":[{"quantity":1.5,"unit":"cup","name":"flour","note":""},
                            {"quantity":"3","unit":"","name":"eggs"},
                            {"quantity":"1/2","unit":"tsp","name":"salt"},
                            {"quantity":null,"unit":"","name":"butter","note":"for the pan"}],
             "steps":["1. Mix the dry ingredients.","Step 2: Whisk in eggs.","Fry."],
             "nutrition":{"calories":"320 kcal","proteinGrams":9}}
            ```
        """.trimIndent()
        val r = RecipePrompts.parseRecipe(answer)
        assertNotNull(r)
        r!!
        assertEquals("Fluffy Pancakes", r.title)
        assertEquals(4, r.servings)
        assertEquals(15, r.cookTimeMinutes)
        assertEquals("Breakfast", r.category)
        assertEquals(listOf("breakfast", "quick"), r.tags)
        assertEquals(Ingredient(1.5, "cup", "flour"), r.ingredients[0])
        assertEquals(3.0, r.ingredients[1].quantity!!, 1e-9)
        assertEquals(0.5, r.ingredients[2].quantity!!, 1e-9)
        assertNull(r.ingredients[3].quantity)
        assertEquals(listOf("Mix the dry ingredients.", "Whisk in eggs.", "Fry."), r.steps)
        assertEquals(320.0, r.nutrition.calories!!, 1e-9)
        assertEquals(9.0, r.nutrition.proteinGrams!!, 1e-9)
    }

    @Test
    fun `rejects non json answers`() {
        assertNull(RecipePrompts.parseRecipe("I can't help with that."))
        assertNull(RecipePrompts.parseNutrition("{}"))
        assertEquals(listOf("vegan", "high protein"), RecipePrompts.parseTags("""{"tags":["Vegan","#High Protein"]}"""))
    }

    @Test
    fun `parses schema org recipe`() {
        val ld = """
            {"@context":"https://schema.org","@graph":[{"@type":"WebPage","name":"x"},
              {"@type":["Recipe"],"name":"Tomato Soup","image":[{"url":"https://img/x.jpg"}],
               "author":{"@type":"Person","name":"Ana"},"recipeYield":["4","4 bowls"],
               "prepTime":"PT10M","cookTime":"PT1H5M","recipeCategory":"Soup","recipeCuisine":"Italian",
               "keywords":"easy, vegetarian","recipeIngredient":["2 tbsp olive oil","800 g tomatoes, chopped","Salt"],
               "recipeInstructions":[{"@type":"HowToSection","itemListElement":[
                   {"@type":"HowToStep","text":"Heat the oil."},{"@type":"HowToStep","text":"Add tomatoes &amp; simmer."}]}],
               "nutrition":{"calories":"180 kcal","sodiumContent":"0.6 g","proteinContent":"4 g"}}]}
        """.trimIndent()
        val r = JsonLdRecipeParser.parse(listOf("not json", ld))
        assertNotNull(r)
        r!!
        assertEquals("Tomato Soup", r.title)
        assertEquals("Ana", r.author)
        assertEquals("https://img/x.jpg", r.imageUrl)
        assertEquals(4, r.servings)
        assertEquals(10, r.prepTimeMinutes)
        assertEquals(65, r.cookTimeMinutes)
        assertEquals("Soup", r.category)
        assertTrue(r.tags.containsAll(listOf("easy", "vegetarian", "italian")))
        assertEquals(Ingredient(800.0, "g", "tomatoes", "chopped"), r.ingredients[1])
        assertEquals(listOf("Heat the oil.", "Add tomatoes & simmer."), r.steps)
        assertEquals(600.0, r.nutrition.sodiumMg!!, 1e-9)
        assertEquals(180.0, r.nutrition.calories!!, 1e-9)
    }

    @Test
    fun `iso durations`() {
        assertEquals(90, JsonLdRecipeParser.isoMinutes("PT1H30M"))
        assertEquals(20, JsonLdRecipeParser.isoMinutes("P0DT0H20M"))
        assertEquals(0, JsonLdRecipeParser.isoMinutes(""))
    }
}
