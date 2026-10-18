package com.stephenjoy.recipes.ui.importer

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stephenjoy.recipes.AppContainer
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.extract.ImportStep
import com.stephenjoy.recipes.extract.LinkResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class ImportUiState(
    val step: ImportStep = ImportStep.FETCHING,
    val existing: Recipe? = null,
    val savedId: Long? = null,
    val savedTitle: String = "",
    val engine: String = "",
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    val running: Boolean get() = savedId == null && error == null && existing == null
}

class ImportViewModel(private val url: String, private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val existing = c.recipes.findBySourceUrl(url)
            if (existing != null) _state.update { it.copy(existing = existing) } else start()
        }
    }

    fun start() {
        job?.cancel()
        _state.value = ImportUiState()
        job = viewModelScope.launch {
            try {
                val result = c.importer.import(url) { step -> _state.update { it.copy(step = step) } }
                val id = c.recipes.save(result.recipe)
                c.recipes.getRecipe(id)?.let { saved -> c.images.download(saved.uid, saved.imageUrl) }
                _state.update {
                    it.copy(
                        step = ImportStep.DONE, savedId = id, savedTitle = result.recipe.title,
                        engine = result.engineLabel, warnings = result.warnings,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Something went wrong") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    url: String,
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onEditRecipe: (Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: ImportViewModel = viewModel(key = url) { ImportViewModel(url, context.container) }
    val state by vm.state.collectAsStateWithLifecycle()

    // Clean import: go straight to the recipe.
    LaunchedEffect(state.savedId, state.warnings) {
        val id = state.savedId
        if (id != null && state.warnings.isEmpty()) onOpenRecipe(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importing recipe") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    Text(LinkResolver.platformOf(url).label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            state.existing?.let { existing ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Already in your recipe box", style = MaterialTheme.typography.titleMedium)
                        Text("“${existing.title}” was saved from this link before.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenRecipe(existing.id) }, shapes = ButtonDefaults.shapes()) { Text("Open it") }
                            OutlinedButton(onClick = vm::start, shapes = ButtonDefaults.shapes()) { Text("Import again") }
                        }
                    }
                }
            }

            AnimatedVisibility(state.running, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                ContainedLoadingIndicator(Modifier.padding(vertical = 16.dp).size(96.dp))
            }

            if (state.existing == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(ImportStep.FETCHING, ImportStep.READING, ImportStep.NUTRITION).forEach { step ->
                        StepRow(step, current = state.step, failed = state.error != null)
                    }
                    AnimatedVisibility(state.running) { LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }

            state.error?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Import failed", style = MaterialTheme.typography.titleMedium)
                        Text(error)
                        Button(onClick = vm::start, shapes = ButtonDefaults.shapes()) { Text("Try again") }
                    }
                }
            }

            val savedId = state.savedId
            if (savedId != null && state.warnings.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text("Saved “${state.savedTitle}”", style = MaterialTheme.typography.titleMediumEmphasized)
                        }
                        if (state.engine.isNotBlank()) Text("Extracted by ${state.engine}", style = MaterialTheme.typography.bodySmall)
                        state.warnings.forEach { warning ->
                            Row {
                                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.size(8.dp))
                                Text(warning, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEditRecipe(savedId) }, shapes = ButtonDefaults.shapes()) { Text("Review & edit") }
                            FilledTonalButton(onClick = { onOpenRecipe(savedId) }, shapes = ButtonDefaults.shapes()) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: ImportStep, current: ImportStep, failed: Boolean) {
    val done = current.ordinal > step.ordinal
    val active = current == step && !failed
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            active -> LoadingIndicator(Modifier.size(28.dp))
            else -> Icon(Icons.Outlined.Circle, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.size(12.dp))
        Text(
            step.label,
            style = if (active) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
            color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
