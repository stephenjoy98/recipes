package com.stephenjoy.recipes.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.formatDuration
import com.stephenjoy.recipes.ui.components.SectionTitle
import com.stephenjoy.recipes.ui.components.ShapeBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(recipeId: Long, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val context = LocalContext.current
    val vm: RecipeEditorViewModel = viewModel(key = "editor-$recipeId") { RecipeEditorViewModel(recipeId, context.container) }
    val s = vm.state
    val tagLibrary by vm.tagSuggestions.collectAsStateWithLifecycle()
    val categories by vm.categoryOptions.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pasteDialog by rememberSaveable { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.update { it.copy(pickedImage = uri) }
    }

    val close: () -> Unit = {
        if (vm.hasChanges()) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }
    BackHandler(onBack = close)

    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == 0L) "New recipe" else "Edit recipe") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                actions = {
                    Button(onClick = { vm.save(onSaved) }, shapes = ButtonDefaults.shapes(), enabled = s.loaded, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        if (!s.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PhotoPicker(
                model = s.pickedImage ?: vm.imageModel(),
                onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            OutlinedTextField(
                value = s.title,
                onValueChange = { v -> vm.update { it.copy(title = v) } },
                label = { Text("Title") },
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = s.description,
                onValueChange = { v -> vm.update { it.copy(description = v) } },
                label = { Text("Description") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionTitle("Category")
            CategoryPicker(s.category, categories) { v -> vm.update { it.copy(category = v) } }

            SectionTitle("Tags", trailing = {
                AssistChip(
                    onClick = vm::suggestTags,
                    label = { Text("Suggest") },
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    enabled = s.busy == null,
                )
            })
            TagEditor(
                tags = s.tags,
                input = s.tagInput,
                library = tagLibrary.filter { it !in s.tags },
                onInput = vm::onTagInput,
                onAdd = { vm.addTag(it) },
                onRemove = vm::removeTag,
            )

            SectionTitle("Time & servings")
            DurationField("Prep time", Icons.Outlined.Timer, s.prepMinutes) { v -> vm.update { it.copy(prepMinutes = v) } }
            Spacer(Modifier.height(12.dp))
            DurationField("Cook time", Icons.Outlined.LocalFireDepartment, s.cookMinutes) { v -> vm.update { it.copy(cookMinutes = v) } }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Servings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { vm.update { it.copy(servings = (it.servings - 1).coerceAtLeast(1)) } }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Fewer servings")
                }
                Text("${s.servings}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                FilledTonalIconButton(onClick = { vm.update { it.copy(servings = it.servings + 1) } }) {
                    Icon(Icons.Filled.Add, contentDescription = "More servings")
                }
            }
            Text(
                "Total ${formatDuration(s.prepMinutes + s.cookMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Ingredients", trailing = {
                TextButton(onClick = { pasteDialog = true }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste list")
                }
            })
            s.ingredients.forEach { ing ->
                IngredientEditorRow(
                    draft = ing,
                    onChange = { transform -> vm.updateIngredient(ing.key, transform) },
                    onRemove = { vm.removeIngredient(ing.key) },
                )
            }
            TextButton(onClick = vm::addIngredient) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add ingredient")
            }

            SectionTitle("Steps")
            s.steps.forEachIndexed { index, step ->
                StepEditorRow(
                    index = index,
                    text = step.text,
                    isFirst = index == 0,
                    isLast = index == s.steps.lastIndex,
                    onChange = { vm.updateStep(step.key, it) },
                    onMove = { vm.moveStep(step.key, it) },
                    onRemove = { vm.removeStep(step.key) },
                )
            }
            TextButton(onClick = vm::addStep) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add step")
            }

            SectionTitle("Nutrition per serving", trailing = {
                AssistChip(
                    onClick = vm::estimateNutrition,
                    label = { Text("Estimate") },
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    enabled = s.busy == null,
                )
            })
            NutritionEditor(s.nutrition) { transform -> vm.update { it.copy(nutrition = transform(it.nutrition)) } }

            SectionTitle("Notes")
            OutlinedTextField(
                value = s.notes,
                onValueChange = { v -> vm.update { it.copy(notes = v) } },
                placeholder = { Text("Substitutions, tips, where you got it…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (s.busy != null) {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.busy)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (pasteDialog) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
            title = { Text("Paste ingredients") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("One per line, e.g.\n2 1/2 cups flour\n3 eggs") },
                    minLines = 6,
                )
            },
            confirmButton = { TextButton(onClick = { vm.pasteIngredients(text); pasteDialog = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhotoPicker(model: Any?, onPick: () -> Unit) {
    Surface(
        onClick = onPick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (model != null) {
                AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (model == null) "Add photo" else "Change photo")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected.equals(option, ignoreCase = true),
                    onClick = { onSelect(if (selected.equals(option, true)) "" else option) },
                    label = { Text(option) },
                )
            }
        }
        OutlinedTextField(
            value = selected,
            onValueChange = onSelect,
            label = { Text("Or type your own category") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    tags: List<String>,
    input: String,
    library: List<String>,
    onInput: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $tag", modifier = Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            label = { Text("Add tags") },
            placeholder = { Text("vegan, quick, high protein") },
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            trailingIcon = {
                if (input.isNotBlank()) IconButton(onClick = { onAdd(input) }) { Icon(Icons.Filled.Add, contentDescription = "Add tag") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd(input) }),
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = library.filter { input.isBlank() || it.contains(input.trim(), ignoreCase = true) }.take(12)
        if (suggestions.isNotEmpty()) {
            Text("From your library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { tag -> SuggestionChip(onClick = { onAdd(tag) }, label = { Text("#$tag") }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationField(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, minutes: Int, onChange: (Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(formatDuration(minutes), style = MaterialTheme.typography.titleLargeEmphasized)
                }
                FilledTonalIconButton(onClick = { onChange((minutes - 5).coerceAtLeast(0)) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Remove, contentDescription = "Less $label")
                }
                OutlinedTextField(
                    value = if (minutes == 0) "" else minutes.toString(),
                    onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
                    suffix = { Text("min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.widthIn(max = 104.dp).padding(horizontal = 4.dp),
                )
                FilledTonalIconButton(onClick = { onChange(minutes + 5) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Add, contentDescription = "More $label")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { onChange(m) }, label = { Text(formatDuration(m)) })
                }
            }
        }
    }
}

@Composable
private fun IngredientEditorRow(
    draft: IngredientDraft,
    onChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.quantity,
                onValueChange = { v -> onChange { it.copy(quantity = v) } },
                label = { Text("Qty") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.width(76.dp),
            )
            OutlinedTextField(
                value = draft.unit,
                onValueChange = { v -> onChange { it.copy(unit = v) } },
                label = { Text("Unit") },
                singleLine = true,
                modifier = Modifier.width(80.dp),
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = { v -> onChange { it.copy(name = v) } },
                label = { Text("Ingredient") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ingredient") }
        }
        OutlinedTextField(
            value = draft.note,
            onValueChange = { v -> onChange { it.copy(note = v) } },
            placeholder = { Text("Note (optional), e.g. finely chopped") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
        )
    }
}

@Composable
private fun StepEditorRow(
    index: Int,
    text: String,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        ShapeBadge("${index + 1}", Modifier.padding(top = 12.dp))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            placeholder = { Text("Describe this step") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f),
        )
        Column {
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Remove step") }
        }
    }
}

@Composable
private fun NutritionEditor(n: NutritionDraft, onChange: ((NutritionDraft) -> NutritionDraft) -> Unit) {
    val fields: List<Triple<String, String, (NutritionDraft, String) -> NutritionDraft>> = listOf(
        Triple("Calories", n.calories) { d, v -> d.copy(calories = v) },
        Triple("Protein (g)", n.protein) { d, v -> d.copy(protein = v) },
        Triple("Carbs (g)", n.carbs) { d, v -> d.copy(carbs = v) },
        Triple("Fat (g)", n.fat) { d, v -> d.copy(fat = v) },
        Triple("Fiber (g)", n.fiber) { d, v -> d.copy(fiber = v) },
        Triple("Sugar (g)", n.sugar) { d, v -> d.copy(sugar = v) },
        Triple("Sodium (mg)", n.sodium) { d, v -> d.copy(sodium = v) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v -> onChange { set(it, v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } },
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        FilledTonalButton(onClick = { onChange { NutritionDraft() } }) { Text("Clear nutrition") }
    }
}
