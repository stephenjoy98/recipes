package com.stephenjoy.recipes.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stephenjoy.recipes.AppContainer
import com.stephenjoy.recipes.ai.RecipePrompts
import com.stephenjoy.recipes.domain.Categories
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.IngredientParser
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Quantities
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.normalizeTag
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class IngredientDraft(
    val key: Long,
    val quantity: String = "",
    val unit: String = "",
    val name: String = "",
    val note: String = "",
)

data class StepDraft(val key: Long, val text: String = "")

data class NutritionDraft(
    val calories: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fiber: String = "",
    val sugar: String = "",
    val sodium: String = "",
)

data class EditorState(
    val loaded: Boolean = false,
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val servings: Int = 2,
    val prepMinutes: Int = 0,
    val cookMinutes: Int = 0,
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<StepDraft> = emptyList(),
    val nutrition: NutritionDraft = NutritionDraft(),
    val notes: String = "",
    val pickedImage: Uri? = null,
    val busy: String? = null,
    val message: String? = null,
)

class RecipeEditorViewModel(private val id: Long, private val c: AppContainer) : ViewModel() {

    var state by mutableStateOf(EditorState())
        private set

    private var original = Recipe()
    private var nextKey = 0L
    private fun key() = nextKey++

    /** Tag suggestions: tags used elsewhere in the library that this recipe doesn't have yet. */
    val tagSuggestions: StateFlow<List<String>> = c.recipes.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Default categories plus any custom ones the user created before. */
    val categoryOptions: StateFlow<List<String>> = combine(
        c.recipes.observeUsedCategories(), kotlinx.coroutines.flow.flowOf(Categories.defaults),
    ) { used, defaults -> defaults + used.filter { u -> defaults.none { it.equals(u, true) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Categories.defaults)

    init {
        viewModelScope.launch {
            val r = if (id != 0L) c.recipes.getRecipe(id) ?: Recipe() else Recipe()
            original = r
            state = r.toState().copy(
                loaded = true,
                ingredients = r.ingredients.map { it.toDraft() }.ifEmpty { listOf(IngredientDraft(key())) },
                steps = r.steps.map { StepDraft(key(), it) }.ifEmpty { listOf(StepDraft(key())) },
            )
        }
    }

    /** Existing photo (local copy or remote URL) when no new one was picked. */
    fun imageModel(): Any? = c.images.model(original.uid, original.imageUrl)

    fun update(transform: (EditorState) -> EditorState) {
        state = transform(state)
    }

    // ----- tags
    fun addTag(raw: String = state.tagInput) {
        val new = raw.split(',', '#').map(::normalizeTag).filter { it.isNotBlank() }
        state = state.copy(tags = (state.tags + new).distinct(), tagInput = "")
    }

    fun onTagInput(text: String) {
        // Typing a comma commits the tag, like most chip inputs.
        if (text.endsWith(",")) addTag(text) else state = state.copy(tagInput = text)
    }

    fun removeTag(tag: String) {
        state = state.copy(tags = state.tags - tag)
    }

    // ----- ingredients
    fun addIngredient() {
        state = state.copy(ingredients = state.ingredients + IngredientDraft(key()))
    }

    fun updateIngredient(key: Long, transform: (IngredientDraft) -> IngredientDraft) {
        state = state.copy(ingredients = state.ingredients.map { if (it.key == key) transform(it) else it })
    }

    fun removeIngredient(key: Long) {
        state = state.copy(ingredients = state.ingredients.filterNot { it.key == key })
    }

    /** Paste a whole ingredient list ("2 cups flour\n3 eggs...") and parse each line. */
    fun pasteIngredients(text: String) {
        val parsed = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { IngredientParser.parse(it).toDraft() }
        val kept = state.ingredients.filter { it.name.isNotBlank() }
        state = state.copy(ingredients = kept + parsed)
    }

    // ----- steps
    fun addStep() {
        state = state.copy(steps = state.steps + StepDraft(key()))
    }

    fun updateStep(key: Long, text: String) {
        state = state.copy(steps = state.steps.map { if (it.key == key) it.copy(text = text) else it })
    }

    fun removeStep(key: Long) {
        state = state.copy(steps = state.steps.filterNot { it.key == key })
    }

    fun moveStep(key: Long, delta: Int) {
        val list = state.steps.toMutableList()
        val i = list.indexOfFirst { it.key == key }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        state = state.copy(steps = list)
    }

    // ----- AI helpers
    fun suggestTags() = runAi("Suggesting tags…") {
        val prompt = RecipePrompts.tagsPrompt(currentRecipe(), tagSuggestions.value)
        val tags = RecipePrompts.parseTags(c.gemini.generate(prompt))
        if (tags.isEmpty()) "Gemini didn't suggest any tags" else {
            state = state.copy(tags = (state.tags + tags).distinct())
            null
        }
    }

    fun estimateNutrition() = runAi("Estimating nutrition…") {
        val recipe = currentRecipe()
        if (recipe.ingredients.isEmpty()) return@runAi "Add ingredients first"
        val n = RecipePrompts.parseNutrition(c.gemini.generate(RecipePrompts.nutritionPrompt(recipe)))
            ?: return@runAi "Couldn't read Gemini's estimate, try again"
        state = state.copy(nutrition = n.toDraft())
        null
    }

    private fun runAi(label: String, block: suspend () -> String?) {
        if (state.busy != null) return
        state = state.copy(busy = label, message = null)
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Gemini isn't available"
            }
            state = state.copy(busy = null, message = message)
        }
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }

    // ----- save
    fun save(onSaved: (Long) -> Unit) {
        val pendingTag = state.tagInput
        if (pendingTag.isNotBlank()) addTag(pendingTag)
        viewModelScope.launch {
            var recipe = currentRecipe()
            if (recipe.uid.isBlank()) recipe = recipe.copy(uid = UUID.randomUUID().toString())
            state.pickedImage?.let { c.images.copyFrom(recipe.uid, it) }
            val savedId = c.recipes.save(recipe)
            onSaved(savedId)
        }
    }

    private fun currentRecipe(): Recipe {
        val s = state
        return original.copy(
            title = s.title.trim(),
            description = s.description.trim(),
            category = s.category.trim(),
            tags = s.tags,
            servings = s.servings.coerceAtLeast(1),
            prepTimeMinutes = s.prepMinutes.coerceAtLeast(0),
            cookTimeMinutes = s.cookMinutes.coerceAtLeast(0),
            ingredients = s.ingredients.filter { it.name.isNotBlank() }.map {
                Ingredient(
                    quantity = Quantities.parse(it.quantity)?.takeIf { q -> q > 0 },
                    unit = it.unit.trim(),
                    name = it.name.trim(),
                    note = it.note.trim(),
                )
            },
            steps = s.steps.map { it.text.trim() }.filter { it.isNotEmpty() },
            nutrition = s.nutrition.toNutrition(),
            notes = s.notes.trim(),
        )
    }

    private fun Recipe.toState() = EditorState(
        title = title,
        description = description,
        category = category,
        tags = tags,
        servings = servings,
        prepMinutes = prepTimeMinutes,
        cookMinutes = cookTimeMinutes,
        nutrition = nutrition.toDraft(),
        notes = notes,
    )

    private fun Ingredient.toDraft() = IngredientDraft(
        key = key(),
        quantity = quantity?.let { Quantities.format(it, unit) }.orEmpty(),
        unit = unit,
        name = name,
        note = note,
    )

    private fun Nutrition.toDraft() = NutritionDraft(
        calories = calories.str(), protein = proteinGrams.str(), carbs = carbsGrams.str(), fat = fatGrams.str(),
        fiber = fiberGrams.str(), sugar = sugarGrams.str(), sodium = sodiumMg.str(),
    )

    private fun NutritionDraft.toNutrition() = Nutrition(
        calories = calories.num(), proteinGrams = protein.num(), carbsGrams = carbs.num(), fatGrams = fat.num(),
        fiberGrams = fiber.num(), sugarGrams = sugar.num(), sodiumMg = sodium.num(),
    )

    private fun Double?.str(): String = this?.let { v ->
        val r = (v * 10).roundToInt() / 10.0
        if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }.orEmpty()

    private fun String.num(): Double? = trim().replace(',', '.').toDoubleOrNull()

    /** Used to block accidental back navigation with unsaved edits. */
    fun hasChanges(): Boolean = state.loaded && (currentRecipe() != original || state.pickedImage != null)
}
