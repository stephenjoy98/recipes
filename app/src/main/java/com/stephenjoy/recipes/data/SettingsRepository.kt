package com.stephenjoy.recipes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class AiEngine(val label: String, val description: String) {
    AUTO("Automatic", "Gemini Nano on this device, Gemini in the cloud when Nano can't help"),
    ON_DEVICE("On-device only", "Gemini Nano via Android AICore. Private and works offline"),
    CLOUD("Cloud", "Gemini through Firebase AI Logic. Can also watch YouTube videos"),
}

data class AppSettings(
    val aiEngine: AiEngine = AiEngine.AUTO,
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    val autoBackup: Boolean = true,
    val dynamicColor: Boolean = true,
    val lastBackupAt: Long = 0,
) {
    companion object {
        const val DEFAULT_CLOUD_MODEL = "gemini-2.5-flash"
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val aiEngine = stringPreferencesKey("ai_engine")
        val cloudModel = stringPreferencesKey("cloud_model")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val lastBackupAt = longPreferencesKey("last_backup_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAiEngine(engine: AiEngine) = edit { it[Keys.aiEngine] = engine.name }
    suspend fun setCloudModel(model: String) = edit {
        it[Keys.cloudModel] = model.trim().ifBlank { AppSettings.DEFAULT_CLOUD_MODEL }
    }
    suspend fun setAutoBackup(enabled: Boolean) = edit { it[Keys.autoBackup] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }
    suspend fun setLastBackupAt(time: Long) = edit { it[Keys.lastBackupAt] = time }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private fun Preferences.toSettings() = AppSettings(
        aiEngine = AiEngine.entries.firstOrNull { it.name == this[Keys.aiEngine] } ?: AiEngine.AUTO,
        cloudModel = this[Keys.cloudModel] ?: AppSettings.DEFAULT_CLOUD_MODEL,
        autoBackup = this[Keys.autoBackup] ?: true,
        dynamicColor = this[Keys.dynamicColor] ?: true,
        lastBackupAt = this[Keys.lastBackupAt] ?: 0,
    )
}
