package com.stephenjoy.recipes.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.stephenjoy.recipes.AppContainer
import com.stephenjoy.recipes.ai.NanoStatus
import com.stephenjoy.recipes.backup.BackupWorker
import com.stephenjoy.recipes.container
import com.stephenjoy.recipes.data.AiEngine
import com.stephenjoy.recipes.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SettingsViewModel(private val app: Context, private val c: AppContainer) : ViewModel() {

    val settings = c.settings.settings
    val account = c.auth.account
    val firebaseConfigured: Boolean get() = c.auth.isConfigured
    val cloudConfigured: Boolean get() = c.cloud.isConfigured()

    private val _nano = MutableStateFlow<NanoStatus>(NanoStatus.Checking)
    val nano: StateFlow<NanoStatus> = _nano.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshNano()
    }

    fun refreshNano() {
        viewModelScope.launch { _nano.value = c.nano.status() }
    }

    fun downloadNano() {
        viewModelScope.launch {
            _nano.value = NanoStatus.Downloading()
            c.nano.download()
                .catch { e -> _nano.value = NanoStatus.Failed(e.message ?: "Download failed") }
                .collect { _nano.value = it }
            if (_nano.value is NanoStatus.Downloading) refreshNano()
        }
    }

    fun signIn(activityContext: Context) = work {
        c.auth.signIn(activityContext)
        val result = c.backup.sync()
        "Signed in. Synced ${result.uploaded} up, ${result.downloaded} down."
    }

    fun signOut() = work {
        c.auth.signOut()
        "Signed out. Your recipes stay on this device."
    }

    fun backupNow() = work {
        val r = c.backup.sync()
        "Backup complete: ${r.uploaded} uploaded, ${r.downloaded} restored, ${r.deleted} removed."
    }

    fun setEngine(engine: AiEngine) {
        viewModelScope.launch { c.settings.setAiEngine(engine) }
    }

    fun setCloudModel(model: String) {
        viewModelScope.launch { c.settings.setCloudModel(model) }
    }

    fun setAnalyzeVideos(on: Boolean) {
        viewModelScope.launch { c.settings.setAnalyzeVideos(on) }
    }

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch { c.settings.setDynamicColor(on) }
    }

    fun setAutoBackup(on: Boolean) {
        viewModelScope.launch {
            c.settings.setAutoBackup(on)
            if (on && c.auth.currentUid != null) BackupWorker.schedulePeriodic(app) else BackupWorker.cancelPeriodic(app)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun work(block: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            _busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(context.applicationContext, context.container) }
    val settings by vm.settings.collectAsStateWithLifecycle(AppSettings())
    val account by vm.account.collectAsStateWithLifecycle()
    val nano by vm.nano.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ------------------------------------------------------------ Account & backup
            SettingsCard(Icons.Outlined.CloudSync, "Google account & backup") {
                val acc = account
                when {
                    !vm.firebaseConfigured -> Text(
                        "Backup isn't set up in this build. Add your Firebase project's google-services.json " +
                            "to the app module and rebuild to turn on Google sign-in and cloud backup.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    acc == null -> {
                        Text(
                            "Sign in with Google to back up your recipes and keep them in sync across your devices.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.signIn(context) }, shapes = ButtonDefaults.shapes(), enabled = !busy) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sign in with Google")
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (acc.photoUrl != null) {
                                AsyncImage(model = acc.photoUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
                            } else {
                                Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(48.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(acc.name.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleMedium)
                                Text(acc.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            if (settings.lastBackupAt > 0) {
                                "Last backup " + DateUtils.getRelativeTimeSpanString(settings.lastBackupAt)
                            } else "Not backed up yet",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SwitchRow("Back up automatically", "After every change and twice a day", settings.autoBackup, vm::setAutoBackup)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = vm::backupNow, shapes = ButtonDefaults.shapes(), enabled = !busy) { Text("Back up & sync now") }
                            OutlinedButton(onClick = vm::signOut, shapes = ButtonDefaults.shapes(), enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sign out")
                            }
                        }
                    }
                }
                if (busy) LoadingIndicator(Modifier.size(40.dp))
            }

            // ------------------------------------------------------------ Gemini
            SettingsCard(Icons.Outlined.AutoAwesome, "Gemini AI") {
                NanoStatusRow(nano, onDownload = vm::downloadNano, onRefresh = vm::refreshNano)
                Text(
                    if (vm.cloudConfigured) "Cloud Gemini (Firebase AI Logic): available"
                    else "Cloud Gemini: not configured (needs google-services.json)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    "Analyse videos",
                    "Download Instagram reels to read on-screen text and let cloud Gemini watch them. Uses data.",
                    settings.analyzeVideos,
                    vm::setAnalyzeVideos,
                )
                HorizontalDivider()
                Text("Which model reads your recipes", style = MaterialTheme.typography.titleSmall)
                AiEngine.entries.forEach { engine ->
                    ListItem(
                        headlineContent = { Text(engine.label) },
                        supportingContent = { Text(engine.description) },
                        leadingContent = { RadioButton(selected = settings.aiEngine == engine, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.selectable(
                            selected = settings.aiEngine == engine,
                            role = Role.RadioButton,
                            onClick = { vm.setEngine(engine) },
                        ),
                    )
                }
                if (settings.aiEngine != AiEngine.ON_DEVICE) {
                    var model by remember(settings.cloudModel) { mutableStateOf(settings.cloudModel) }
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Cloud model") },
                        supportingText = { Text("Gemini model name. Default ${AppSettings.DEFAULT_CLOUD_MODEL}") },
                        singleLine = true,
                        trailingIcon = {
                            if (model.trim() != settings.cloudModel) {
                                androidx.compose.material3.TextButton(onClick = { vm.setCloudModel(model) }) { Text("Save") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ------------------------------------------------------------ Appearance
            SettingsCard(Icons.Outlined.Palette, "Appearance") {
                val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SwitchRow(
                    "Material You colors",
                    if (supported) "Match the app to your wallpaper" else "Needs Android 12 or newer",
                    settings.dynamicColor && supported,
                    onChange = vm::setDynamicColor,
                    enabled = supported,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun NanoStatusRow(status: NanoStatus, onDownload: () -> Unit, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gemini Nano (on device)", style = MaterialTheme.typography.titleSmall)
        val text = when (status) {
            NanoStatus.Checking -> "Checking…"
            NanoStatus.Available -> "Ready. Recipes are read privately on your phone."
            NanoStatus.Downloadable -> "Supported on this phone. Download the model to use it."
            is NanoStatus.Downloading -> "Downloading" + if (status.bytes > 0) " (${status.bytes / 1_000_000} MB)…" else "…"
            NanoStatus.Unavailable -> "Not available on this device. Gemini Nano needs a phone with Android AICore " +
                "(e.g. Pixel 9 or newer, recent Galaxy S). Cloud Gemini is used instead when configured."
            is NanoStatus.Failed -> "Download failed: ${status.message}"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        when (status) {
            NanoStatus.Downloadable, is NanoStatus.Failed -> FilledTonalButton(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download Gemini Nano")
            }
            is NanoStatus.Downloading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            NanoStatus.Unavailable -> OutlinedButton(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text("Check again") }
            else -> Unit
        }
    }
}
