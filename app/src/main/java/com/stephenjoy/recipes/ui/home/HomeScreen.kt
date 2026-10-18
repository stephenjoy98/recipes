package com.stephenjoy.recipes.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.domain.formatDuration
import com.stephenjoy.recipes.extract.LinkResolver
import com.stephenjoy.recipes.ui.components.InfoPill
import com.stephenjoy.recipes.ui.components.RecipeImage
import com.stephenjoy.recipes.ui.components.icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit,
    onImportLink: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel { HomeViewModel(context.container.recipes) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Recipe Box", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        IconButton(onClick = { showSort = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.label) },
                                    leadingIcon = { RadioButton(selected = state.filters.sort == order, onClick = null) },
                                    onClick = { vm.setSort(order); showSort = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add recipe") },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchField(state.filters.query, vm::setQuery)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterRows(state, vm)
            }
            if (!state.loading && state.recipes.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (state.totalCount == 0) EmptyLibrary(onPaste = { showLinkDialog = true })
                    else NoMatches(onClear = vm::clearFilters)
                }
            }
            items(state.recipes, key = { it.id }) { recipe ->
                RecipeCard(recipe, onClick = { onOpenRecipe(recipe.id) }, onFavorite = { vm.toggleFavorite(recipe) })
            }
        }
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, sheetState = sheetState) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    "Add a recipe",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("From a link") },
                    supportingContent = { Text("Instagram, YouTube, Shorts, TikTok or any recipe site") },
                    leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; showLinkDialog = true },
                )
                ListItem(
                    headlineContent = { Text("Write it myself") },
                    supportingContent = { Text("Start from a blank recipe") },
                    leadingContent = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                    modifier = Modifier.clickable { showAddSheet = false; onNewRecipe() },
                )
                Text(
                    "Tip: in any app, tap Share on a post and choose “Save recipe”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onImport = { showLinkDialog = false; onImportLink(it) },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search recipes, ingredients, tags") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun FilterRows(state: HomeUiState, vm: HomeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Categories
        val favoriteIcon: @Composable () -> Unit = {
            Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories) { category ->
                FilterChip(
                    selected = state.filters.category == category,
                    onClick = { vm.setCategory(category) },
                    label = { Text(category) },
                    leadingIcon = favoriteIcon.takeIf { category == FAVORITES },
                )
            }
        }
        // Prep time + tags
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(15, 30, 60)) { minutes ->
                val selected = state.filters.maxMinutes == minutes
                FilterChip(
                    selected = selected,
                    onClick = { vm.setMaxMinutes(if (selected) null else minutes) },
                    label = { Text("≤ ${formatDuration(minutes)}") },
                    leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
            items(state.tags.take(30)) { tag ->
                FilterChip(
                    selected = tag in state.filters.tags,
                    onClick = { vm.toggleTag(tag) },
                    label = { Text(tag) },
                    leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onFavorite: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box {
            RecipeImage(recipe, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).clickable(onClick = onFavorite),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (recipe.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (recipe.favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (recipe.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (recipe.totalTimeMinutes > 0) InfoPill(Icons.Outlined.Timer, formatDuration(recipe.totalTimeMinutes))
                InfoPill(recipe.sourcePlatform.icon(), recipe.sourcePlatform.label)
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(
                recipe.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(recipe.category.takeIf { it.isNotBlank() }) + recipe.tags.take(2).map { "#$it" }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onPaste: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Your recipe box is empty", style = MaterialTheme.typography.titleLarge)
        Text(
            "Found something tasty on Instagram or YouTube? Tap Share and pick “Save recipe”. " +
                "Gemini pulls out the ingredients, steps and nutrition for you.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onPaste) { Text("Paste a link") }
    }
}

@Composable
private fun NoMatches(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No recipes match these filters", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClear) { Text("Clear filters") }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable {
        mutableStateOf(LinkResolver.extractUrl(clipboard.getText()?.text).orEmpty())
    }
    val url = LinkResolver.extractUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text("Import from a link") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Post or video link") },
                placeholder = { Text("https://www.instagram.com/reel/…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { url?.let(onImport) }),
                isError = text.isNotBlank() && url == null,
                supportingText = { if (text.isNotBlank() && url == null) Text("That doesn't look like a link") },
            )
        },
        confirmButton = { TextButton(onClick = { url?.let(onImport) }, enabled = url != null) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
