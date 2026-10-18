package com.stephenjoy.recipes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScalingTest {

    private val cake = listOf(
        Ingredient(3.0, "", "eggs"),
        Ingredient(1.5, "cup", "flour"),
        Ingredient(200.0, "g", "sugar"),
        Ingredient(null, "", "salt", "to taste"),
    )

    @Test
    fun `changing eggs from 3 to 4 scales everything by 4 over 3`() {
        val factor = Scaling.factorFor(cake[0], 4.0)!!
        val scaled = Scaling.scale(cake, factor)
        assertEquals(4.0, scaled[0].quantity!!, 1e-9)
        assertEquals(2.0, scaled[1].quantity!!, 1e-9)
        assertEquals(266.666, scaled[2].quantity!!, 1e-3)
        assertNull(scaled[3].quantity)
        assertEquals("4", Quantities.format(scaled[0].quantity!!))
        assertEquals("2", Quantities.format(scaled[1].quantity!!, "cup"))
        assertEquals("267", Quantities.format(scaled[2].quantity!!, "g"))
    }

    @Test
    fun `servings factor`() {
        assertEquals(1.5, Scaling.factorForServings(4, 6), 1e-9)
        assertEquals(6, Scaling.scaledServings(4, 1.5))
        assertEquals("×1.33", Scaling.formatFactor(4.0 / 3))
        assertEquals("×2", Scaling.formatFactor(2.0))
    }

    @Test
    fun `unscalable ingredient yields no factor`() {
        assertNull(Scaling.factorFor(cake[3], 2.0))
        assertNull(Scaling.factorFor(cake[0], 0.0))
    }

    @Test
    fun `formats kitchen fractions`() {
        assertEquals("½", Quantities.format(0.5, "cup"))
        assertEquals("1⅓", Quantities.format(4.0 / 3, "tbsp"))
        assertEquals("2¾", Quantities.format(2.75))
        assertEquals("0.3", Quantities.format(0.3, "g"))
        assertEquals("1.1", Quantities.format(1.1))
    }

    @Test
    fun `parses quantities`() {
        assertEquals(2.0, Quantities.parse("2")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1 1/2")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1½")!!, 1e-9)
        assertEquals(0.75, Quantities.parse("3/4")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1,5")!!, 1e-9)
        assertEquals(1.0, Quantities.parse("1-2")!!, 1e-9)
        assertNull(Quantities.parse("a pinch"))
    }

    @Test
    fun `parses ingredient lines`() {
        assertEquals(Ingredient(2.5, "cup", "flour", "sifted"), IngredientParser.parse("2 1/2 cups flour, sifted"))
        assertEquals(Ingredient(3.0, "", "large eggs"), IngredientParser.parse("3 large eggs"))
        assertEquals(Ingredient(200.0, "g", "butter", "softened"), IngredientParser.parse("200g butter (softened)"))
        assertEquals(Ingredient(1.0, "tbsp", "olive oil"), IngredientParser.parse("1 Tablespoon of olive oil"))
        assertEquals(Ingredient(0.5, "tsp", "salt"), IngredientParser.parse("½ tsp. salt"))
        assertEquals(Ingredient(null, "", "Salt and pepper", "to taste"), IngredientParser.parse("Salt and pepper, to taste"))
    }

    @Test
    fun `normalises tags`() {
        assertEquals("high protein", normalizeTag("  #High   Protein "))
    }
}
