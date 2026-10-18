package com.stephenjoy.recipes.ui.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.Ingredient
import com.stephenjoy.recipes.domain.Nutrition
import com.stephenjoy.recipes.domain.Quantities
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.Scaling
import com.stephenjoy.recipes.domain.displayAmount
import com.stephenjoy.recipes.domain.formatDuration
import com.stephenjoy.recipes.ui.components.RecipeImage
import com.stephenjoy.recipes.ui.components.SectionTitle
import com.stephenjoy.recipes.ui.components.StatBlock
import com.stephenjoy.recipes.ui.components.icon
import kotlin.math.roundToInt

@Composable
fun RecipeDetailScreen(recipeId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val vm: RecipeDetailViewModel = viewModel(key = "detail-$recipeId") { RecipeDetailViewModel(recipeId, context.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recipe = state.recipe

    if (recipe == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator() else Text("This recipe no longer exists")
        }
        return
    }

    var scalingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onEdit,
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text("Edit") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item { RecipeImage(recipe, Modifier.fillMaxWidth().height(300.dp)) }
                item { Header(recipe, onOpenSource = { runCatching { uriHandler.openUri(recipe.sourceUrl) } }) }
                item { TimeRow(recipe) }
                item {
                    ServingsCard(
                        state = state,
                        onStep = vm::stepServings,
                        onFactor = vm::setFactor,
                        onReset = vm::resetScale,
                        onSaveAsBase = vm::saveScaledAsBase,
                    )
                }
                item {
                    SectionTitle(
                        "Ingredients",
                        Modifier.padding(horizontal = 16.dp),
                        trailing = {
                            if (state.isScaled) {
                                Text(
                                    Scaling.formatFactor(state.factor),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                    if (recipe.ingredients.any { it.quantity != null }) {
                        Text(
                            "Tap an amount to change it. Everything else adjusts to match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                itemsIndexed(state.scaledIngredients) { index, ingredient ->
                    IngredientRow(
                        ingredient = ingredient,
                        scaled = state.isScaled,
                        checked = index in state.cooking.checkedIngredients,
                        onToggle = { vm.toggleIngredient(index) },
                        onAmountClick = { scalingIndex = index },
                    )
                }
                if (recipe.steps.isNotEmpty()) {
                    item { SectionTitle("Steps", Modifier.padding(horizontal = 16.dp)) }
                    itemsIndexed(recipe.steps) { index, step ->
                        StepRow(index, step, done = index in state.cooking.doneSteps, onToggle = { vm.toggleStep(index) })
                    }
                }
                if (!recipe.nutrition.isEmpty) {
                    item {
                        NutritionCard(
                            nutrition = state.shownNutrition,
                            forAll = state.cooking.nutritionForAll,
                            servingsLabel = state.servingsLabel,
                            onForAll = vm::setNutritionForAll,
                        )
                    }
                }
                if (recipe.notes.isNotBlank()) {
                    item {
                        SectionTitle("Notes", Modifier.padding(horizontal = 16.dp))
                        Text(recipe.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            // Floating top actions over the photo.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                )
                FilledTonalIconButton(onClick = onBack, colors = colors) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = vm::toggleFavorite, colors = colors) {
                    Icon(
                        if (recipe.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (recipe.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.shareText())
                        context.startActivity(Intent.createChooser(send, "Share recipe"))
                    },
                    colors = colors,
                ) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                Box {
                    FilledTonalIconButton(onClick = { menu = true }, colors = colors) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (recipe.sourceUrl.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Open original post") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                onClick = { menu = false; runCatching { uriHandler.openUri(recipe.sourceUrl) } },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    scalingIndex?.let { index ->
        val original = recipe.ingredients.getOrNull(index)
        val current = state.scaledIngredients.getOrNull(index)
        if (original != null && current != null) {
            ScaleIngredientDialog(
                original = original,
                current = current,
                onDismiss = { scalingIndex = null },
                onApply = { qty -> if (vm.scaleByIngredient(index, qty)) scalingIndex = null },
            )
        } else {
            scalingIndex = null
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Delete recipe?") },
            text = { Text("“${recipe.title}” will be removed from this device and from your backup.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(recipe: Recipe, onOpenSource: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(recipe.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (recipe.sourceUrl.isNotBlank()) {
            Row(
                Modifier.clickable(onClick = onOpenSource),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(recipe.sourcePlatform.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(recipe.sourcePlatform.label, recipe.author.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (recipe.description.isNotBlank()) {
            Text(recipe.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recipe.category.isNotBlank() || recipe.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recipe.category.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(recipe.category) },
                        leadingIcon = { Icon(Icons.Outlined.Category, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    )
                }
                recipe.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text("#$tag") }) }
            }
        }
    }
}

@Composable
private fun TimeRow(recipe: Recipe) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatBlock(Icons.Outlined.Timer, formatDuration(recipe.prepTimeMinutes), "Prep", Modifier.weight(1f))
        StatBlock(Icons.Outlined.LocalFireDepartment, formatDuration(recipe.cookTimeMinutes), "Cook", Modifier.weight(1f))
        StatBlock(Icons.Outlined.Schedule, formatDuration(recipe.totalTimeMinutes), "Total", Modifier.weight(1f))
    }
}

@Composable
private fun ServingsCard(
    state: DetailUiState,
    onStep: (Int) -> Unit,
    onFactor: (Double) -> Unit,
    onReset: () -> Unit,
    onSaveAsBase: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Restaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Servings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (state.isScaled) {
                        Text(
                            "Recipe makes ${state.recipe?.servings} · scaled ${Scaling.formatFactor(state.factor)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                FilledTonalIconButton(onClick = { onStep(-1) }, enabled = state.servings > 1.0001) {
                    Icon(Icons.Filled.Remove, contentDescription = "Fewer servings")
                }
                Text(
                    state.servingsLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.widthIn(min = 48.dp).padding(horizontal = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FilledTonalIconButton(onClick = { onStep(1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "More servings")
                }
            }
            val presets = listOf(0.5, 1.0, 1.5, 2.0, 3.0)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                presets.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = kotlin.math.abs(state.factor - f) < 1e-6,
                        onClick = { onFactor(f) },
                        shape = SegmentedButtonDefaults.itemShape(i, presets.size),
                        icon = {},
                    ) { Text(Scaling.formatFactor(f)) }
                }
            }
            if (state.isScaled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReset) { Text("Reset") }
                    FilledTonalButton(onClick = onSaveAsBase) { Text("Save as new amounts") }
                }
            }
        }
    }
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    scaled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onAmountClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        val amount = ingredient.displayAmount()
        if (amount.isNotBlank()) {
            Surface(
                onClick = onAmountClick,
                enabled = ingredient.quantity != null,
                shape = CircleShape,
                color = if (scaled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (scaled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(amount, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (ingredient.quantity != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Scale, contentDescription = "Change amount", modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                ingredient.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (ingredient.note.isNotBlank()) {
                Text(ingredient.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, step: String, done: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            contentColor = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelLarge) }
        }
        Text(
            step,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NutritionCard(nutrition: Nutrition, forAll: Boolean, servingsLabel: String, onForAll: (Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionTitle("Nutrition")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !forAll, onClick = { onForAll(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("Per serving")
            }
            SegmentedButton(selected = forAll, onClick = { onForAll(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("All $servingsLabel servings")
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp)) {
                nutrition.calories?.let {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${it.roundToInt()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("kcal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }
                val rows = listOf(
                    "Protein" to nutrition.proteinGrams?.let { "${fmt(it)} g" },
                    "Carbohydrates" to nutrition.carbsGrams?.let { "${fmt(it)} g" },
                    "Fat" to nutrition.fatGrams?.let { "${fmt(it)} g" },
                    "Fiber" to nutrition.fiberGrams?.let { "${fmt(it)} g" },
                    "Sugar" to nutrition.sugarGrams?.let { "${fmt(it)} g" },
                    "Sodium" to nutrition.sodiumMg?.let { "${it.roundToInt()} mg" },
                ).filter { it.second != null }
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(value!!, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    "Estimates. Check labels if you need exact values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun fmt(v: Double): String = if (v >= 10) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).let {
    if (it == kotlin.math.floor(it)) it.toInt().toString() else it.toString()
}

/** "I'm using 4 eggs instead of 3": asks for the amount actually used and rescales the recipe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleIngredientDialog(
    original: Ingredient,
    current: Ingredient,
    onDismiss: () -> Unit,
    onApply: (Double) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current.quantity?.let { Quantities.format(it, current.unit) }.orEmpty()) }
    val parsed = Quantities.parse(text)
    val unitLabel = original.unit.ifBlank { original.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Scale, contentDescription = null) },
        title = { Text("Adjust by ${original.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The recipe uses ${original.displayAmount()} ${if (original.unit.isBlank()) "" else original.name}".trim() +
                        ". How much are you using? All other ingredients will be adjusted to keep the same ratio.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount") },
                    suffix = { Text(unitLabel) },
                    singleLine = true,
                    isError = text.isNotBlank() && (parsed == null || parsed <= 0.0),
                    supportingText = { Text("Fractions like 1 1/2 or ¾ work too") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                val base = original.quantity ?: 0.0
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Counted items (eggs) step by one; measured ones by common ratios.
                    val suggestions = if (original.unit.isBlank()) listOf(base - 1, base + 1, base * 2) else listOf(base * 0.5, base * 1.5, base * 2)
                    suggestions.filter { it > 0 }.distinct().forEach { q ->
                        InputChip(
                            selected = parsed != null && kotlin.math.abs(parsed - q) < 1e-6,
                            onClick = { text = Quantities.format(q, original.unit) },
                            label = { Text("${Quantities.format(q, original.unit)} $unitLabel") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null && parsed > 0.0) { Text("Adjust recipe") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
