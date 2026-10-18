package com.stephenjoy.recipes.ui.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.domain.Recipe
import com.stephenjoy.recipes.export.ExportFile
import com.stephenjoy.recipes.export.ExportFormat
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One set of recipes the user can choose to export, e.g. "All 24 recipes" or "12 shown". */
data class ExportChoice(val label: String, val recipes: List<Recipe>)

/** System "Save as…" dialog (Storage Access Framework) with the MIME type chosen at launch time. */
private class CreateDocument : ActivityResultContract<ExportFile, Uri?>() {
    override fun createIntent(context: Context, input: ExportFile): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
fun ExportSheet(choices: List<ExportChoice>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exporter = context.container.exporter
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var formatName by rememberSaveable { mutableStateOf(ExportFormat.PDF.name) }
    val format = ExportFormat.valueOf(formatName)
    var choiceIndex by rememberSaveable { mutableIntStateOf(0) }
    val choice = choices.getOrElse(choiceIndex) { choices.first() }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExportFile?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val saveLauncher = rememberLauncherForActivityResult(CreateDocument()) { uri ->
        val file = pending
        pending = null
        if (uri == null || file == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                exporter.saveTo(uri, file)
                toast("Saved ${file.name}")
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't save: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun run(action: suspend (ExportFile) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action(exporter.export(choice.recipes, format))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Text(
                if (choice.recipes.size == 1) "Export “${choice.recipes.first().title}”" else "Export ${choice.recipes.size} recipes",
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (choices.size > 1) {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEachIndexed { i, c ->
                        FilterChip(selected = i == choiceIndex, onClick = { choiceIndex = i }, label = { Text(c.label) })
                    }
                }
            }
            ExportFormat.entries.forEach { f ->
                val fileNote = if (f == ExportFormat.COOK && choice.recipes.size > 1) ".zip of .cook files" else ".${f.extension}"
                ListItem(
                    headlineContent = { Text(f.label) },
                    supportingContent = { Text(f.description) },
                    leadingContent = { RadioButton(selected = f == format, onClick = null) },
                    trailingContent = { Text(fileNote, style = MaterialTheme.typography.labelMedium) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = f == format, role = Role.RadioButton) { formatName = f.name },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { run { file -> pending = file; saveLauncher.launch(file) } },
                    shapes = ButtonDefaults.shapes(),
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Save to device")
                }
                FilledTonalButton(
                    onClick = {
                        run { file ->
                            context.startActivity(exporter.shareIntent(file))
                            busy = false
                            onDismiss()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
                if (busy) LoadingIndicator(Modifier.size(40.dp))
            }
        }
    }
}
