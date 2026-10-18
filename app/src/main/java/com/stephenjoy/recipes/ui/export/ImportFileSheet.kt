package com.stephenjoy.recipes.ui.export

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.formatDuration
import com.stephenjoy.recipes.export.FileReadResult
import com.stephenjoy.recipes.export.RecipeFileImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface ImportFileState {
    data object Reading : ImportFileState
    data class Ready(val result: FileReadResult, val selected: Set<Int>) : ImportFileState
    data object Saving : ImportFileState
    data class Done(val added: Int, val updated: Int) : ImportFileState
    data class Failed(val message: String) : ImportFileState
}

class ImportFileViewModel(private val uri: Uri, private val importer: RecipeFileImporter) : ViewModel() {
    private val _state = MutableStateFlow<ImportFileState>(ImportFileState.Reading)
    val state: StateFlow<ImportFileState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val result = importer.read(uri)
                ImportFileState.Ready(result, result.candidates.indices.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportFileState.Failed(e.message ?: "Couldn't read the file")
            }
        }
    }

    fun toggle(index: Int) = _state.update { s ->
        if (s is ImportFileState.Ready) s.copy(selected = if (index in s.selected) s.selected - index else s.selected + index) else s
    }

    fun toggleAll() = _state.update { s ->
        if (s is ImportFileState.Ready) {
            s.copy(selected = if (s.selected.size == s.result.candidates.size) emptySet() else s.result.candidates.indices.toSet())
        } else s
    }

    fun importSelected() {
        val ready = _state.value as? ImportFileState.Ready ?: return
        _state.value = ImportFileState.Saving
        viewModelScope.launch {
            _state.value = try {
                val chosen = ready.result.candidates.filterIndexed { i, _ -> i in ready.selected }
                val (added, updated) = importer.save(chosen)
                ImportFileState.Done(added, updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportFileState.Failed(e.message ?: "Import failed")
            }
        }
    }
}

@Composable
fun ImportFileSheet(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vm: ImportFileViewModel = viewModel(key = "import-$uri") { ImportFileViewModel(uri, context.container.fileImporter) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state) {
        val done = state as? ImportFileState.Done ?: return@LaunchedEffect
        val parts = listOfNotNull(
            done.added.takeIf { it > 0 }?.let { "$it added" },
            done.updated.takeIf { it > 0 }?.let { "$it updated" },
        )
        Toast.makeText(context, "Imported: " + parts.joinToString(", ").ifBlank { "nothing" }, Toast.LENGTH_LONG).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            when (val s = state) {
                ImportFileState.Reading, ImportFileState.Saving, is ImportFileState.Done -> Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LoadingIndicator(Modifier.size(64.dp))
                    Text(if (s == ImportFileState.Reading) "Reading recipes…" else "Saving…", style = MaterialTheme.typography.titleMedium)
                }

                is ImportFileState.Failed -> Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't import", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Close") }
                }

                is ImportFileState.Ready -> {
                    val candidates = s.result.candidates
                    Text(
                        if (candidates.size == 1) "Import 1 recipe" else "Import ${candidates.size} recipes",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(
                        s.result.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (s.result.usedGemini) {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                "  Gemini read this file. Check the recipe after importing.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (candidates.size > 1) {
                        TextButton(onClick = vm::toggleAll, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(if (s.selected.size == candidates.size) "Select none" else "Select all")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(candidates) { i, c ->
                            val r = c.recipe
                            val details = listOfNotNull(
                                "${r.ingredients.size} ingredients",
                                "${r.steps.size} steps",
                                r.totalTimeMinutes.takeIf { it > 0 }?.let { formatDuration(it) },
                                r.category.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            ListItem(
                                headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(if (c.existingId != null) "Updates the copy in your library · $details" else details)
                                },
                                leadingContent = { Checkbox(checked = i in s.selected, onCheckedChange = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.toggleable(value = i in s.selected, role = Role.Checkbox) { vm.toggle(i) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Button(
                            onClick = vm::importSelected,
                            enabled = s.selected.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (s.selected.size == 1) "Import recipe" else "Import ${s.selected.size} recipes")
                        }
                    }
                }
            }
        }
    }
}
