package com.stephenjoy.recipes.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stephenjoy.recipes.AppContainer
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Quantities
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.Scaling
import com.stephenjoy.recipes.export.RecipeFormats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CookingState(
    /** Multiplier applied to every ingredient (1.0 = the recipe as saved). */
    val factor: Double = 1.0,
    val checkedIngredients: Set<Int> = emptySet(),
    val doneSteps: Set<Int> = emptySet(),
    val nutritionForAll: Boolean = false,
)

data class DetailUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val cooking: CookingState = CookingState(),
) {
    val factor: Double get() = cooking.factor
    val isScaled: Boolean get() = kotlin.math.abs(cooking.factor - 1.0) > 1e-6
    val scaledIngredients: List<Ingredient> get() = recipe?.let { Scaling.scale(it.ingredients, factor) }.orEmpty()
    val servings: Double get() = (recipe?.servings ?: 1) * factor
    val servingsLabel: String get() = Quantities.format(servings)

    /** Per serving stays the same when scaling; "whole recipe" multiplies by servings. */
    val shownNutrition: Nutrition
        get() {
            val n = recipe?.nutrition ?: return Nutrition()
            if (!cooking.nutritionForAll) return n
            val k = servings
            return Nutrition(
                n.calories?.times(k), n.proteinGrams?.times(k), n.carbsGrams?.times(k), n.fatGrams?.times(k),
                n.fiberGrams?.times(k), n.sugarGrams?.times(k), n.sodiumMg?.times(k),
            )
        }
}

class RecipeDetailViewModel(private val id: Long, private val c: AppContainer) : ViewModel() {

    private val cooking = MutableStateFlow(CookingState())

    val state: StateFlow<DetailUiState> = combine(c.recipes.observeRecipe(id), cooking) { recipe, cook ->
        DetailUiState(loading = false, recipe = recipe, cooking = cook)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private val base: Recipe? get() = state.value.recipe

    fun setServings(servings: Int) {
        val r = base ?: return
        cooking.update { it.copy(factor = Scaling.factorForServings(r.servings, servings.coerceAtLeast(1))) }
    }

    fun stepServings(delta: Int) {
        val current = state.value.servings
        val next = if (delta > 0) kotlin.math.floor(current + 1e-6).toInt() + 1 else kotlin.math.ceil(current - 1e-6).toInt() - 1
        setServings(next.coerceAtLeast(1))
    }

    fun setFactor(factor: Double) = cooking.update { it.copy(factor = factor.coerceIn(0.05, 100.0)) }

    /**
     * "I'm using 4 eggs instead of 3": derive the factor from one ingredient so that every other
     * ingredient keeps its proportion.
     */
    fun scaleByIngredient(index: Int, newQuantity: Double): Boolean {
        val original = base?.ingredients?.getOrNull(index) ?: return false
        val factor = Scaling.factorFor(original, newQuantity) ?: return false
        setFactor(factor)
        return true
    }

    fun resetScale() = cooking.update { it.copy(factor = 1.0) }

    /** Make the current scaled amounts the recipe's new base amounts. */
    fun saveScaledAsBase() {
        val r = base ?: return
        val s = state.value
        viewModelScope.launch {
            c.recipes.save(
                r.copy(
                    ingredients = s.scaledIngredients,
                    servings = s.servings.roundToInt().coerceAtLeast(1),
                ),
            )
            cooking.update { it.copy(factor = 1.0) }
        }
    }

    fun toggleIngredient(index: Int) = cooking.update {
        it.copy(checkedIngredients = it.checkedIngredients.toggle(index))
    }

    fun toggleStep(index: Int) = cooking.update { it.copy(doneSteps = it.doneSteps.toggle(index)) }

    fun setNutritionForAll(all: Boolean) = cooking.update { it.copy(nutritionForAll = all) }

    fun toggleFavorite() {
        val r = base ?: return
        viewModelScope.launch { c.recipes.setFavorite(r.id, !r.favorite) }
    }

    fun delete(onDone: () -> Unit) {
        val r = base ?: return
        viewModelScope.launch {
            c.recipes.delete(r.id)
            c.images.delete(r.uid)
            onDone()
        }
    }

    /** Plain-text version for sharing, using the currently scaled amounts. */
    fun shareText(): String {
        val s = state.value
        val r = s.recipe ?: return ""
        return RecipeFormats.textOf(r, s.scaledIngredients, s.servingsLabel)
    }

    private fun Set<Int>.toggle(i: Int) = if (i in this) this - i else this + i
}
