package com.stephenjoy.recipes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stephenjoy.recipes.data.RecipeRepository
import com.stephenjoy.recipes.domain.Categories
import com.stephenjoy.recipes.domain.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortOrder(val label: String) { RECENT("Recently updated"), TITLE("A to Z"), QUICKEST("Quickest first") }

/** Special pseudo-categories at the start of the category chip row. */
const val ALL = "All"
const val FAVORITES = "Favorites"

data class HomeFilters(
    val query: String = "",
    val category: String = ALL,
    val tags: Set<String> = emptySet(),
    val maxMinutes: Int? = null,
    val sort: SortOrder = SortOrder.RECENT,
)

data class HomeUiState(
    val loading: Boolean = true,
    val recipes: List<Recipe> = emptyList(),
    val totalCount: Int = 0,
    val categories: List<String> = listOf(ALL, FAVORITES),
    val tags: List<String> = emptyList(),
    val filters: HomeFilters = HomeFilters(),
)

class HomeViewModel(private val repo: RecipeRepository) : ViewModel() {

    private val filters = MutableStateFlow(HomeFilters())

    val state: StateFlow<HomeUiState> = combine(
        repo.observeRecipes(), repo.observeTags(), filters,
    ) { all, tags, f ->
        val usedCategories = all.map { it.category }.filter { it.isNotBlank() }.distinct()
        // Defaults that are in use first, then custom ones, keeping the familiar order.
        val categories = Categories.defaults.filter { it in usedCategories } +
            usedCategories.filter { it !in Categories.defaults }.sorted()
        HomeUiState(
            loading = false,
            recipes = all.filter { it.matches(f) }.sortedWith(f.sort.comparator()),
            totalCount = all.size,
            categories = listOf(ALL, FAVORITES) + categories,
            tags = tags,
            filters = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setCategory(c: String) = filters.update { it.copy(category = c) }
    fun toggleTag(tag: String) = filters.update { f -> f.copy(tags = if (tag in f.tags) f.tags - tag else f.tags + tag) }
    fun setMaxMinutes(minutes: Int?) = filters.update { it.copy(maxMinutes = minutes) }
    fun setSort(sort: SortOrder) = filters.update { it.copy(sort = sort) }
    fun clearFilters() = filters.update { HomeFilters(sort = it.sort) }

    fun toggleFavorite(recipe: Recipe) {
        viewModelScope.launch { repo.setFavorite(recipe.id, !recipe.favorite) }
    }

    private fun Recipe.matches(f: HomeFilters): Boolean {
        if (f.category == FAVORITES && !favorite) return false
        if (f.category != ALL && f.category != FAVORITES && !category.equals(f.category, ignoreCase = true)) return false
        if (f.tags.isNotEmpty() && !tags.containsAll(f.tags)) return false
        if (f.maxMinutes != null && (totalTimeMinutes == 0 || totalTimeMinutes > f.maxMinutes)) return false
        val q = f.query.trim()
        if (q.isEmpty()) return true
        return title.contains(q, true) || description.contains(q, true) || category.contains(q, true) ||
            author.contains(q, true) || tags.any { it.contains(q, true) } ||
            ingredients.any { it.name.contains(q, true) }
    }

    private fun SortOrder.comparator(): Comparator<Recipe> = when (this) {
        SortOrder.RECENT -> compareByDescending { it.updatedAt }
        SortOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortOrder.QUICKEST -> compareBy<Recipe> { if (it.totalTimeMinutes == 0) Int.MAX_VALUE else it.totalTimeMinutes }
            .thenBy { it.title }
    }
}
