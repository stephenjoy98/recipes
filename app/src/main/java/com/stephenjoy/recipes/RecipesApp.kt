package com.stephenjoy.recipes

import android.app.Application
import android.content.Context
import com.stephenjoy.recipes.ai.CloudGemini
import com.stephenjoy.recipes.ai.GeminiRouter
import com.stephenjoy.recipes.ai.OnDeviceGemini
import com.stephenjoy.recipes.backup.AuthManager
import com.stephenjoy.recipes.backup.BackupRepository
import com.stephenjoy.recipes.backup.BackupWorker
import com.stephenjoy.recipes.data.ImageStore
import com.stephenjoy.recipes.data.RecipeRepository
import com.stephenjoy.recipes.data.SettingsRepository
import com.stephenjoy.recipes.data.local.AppDatabase
import com.stephenjoy.recipes.export.RecipeExporter
import com.stephenjoy.recipes.export.RecipeFileImporter
import com.stephenjoy.recipes.extract.ContentFetcher
import com.stephenjoy.recipes.extract.RecipeImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Simple manual dependency container shared by all screens. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val database = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val recipes = RecipeRepository(database.recipeDao())
    val images = ImageStore(context, http)
    val nano = OnDeviceGemini()
    val cloud = CloudGemini(context)
    val importer = RecipeImporter(ContentFetcher(http), nano, cloud, settings)
    val gemini = GeminiRouter(nano, cloud, settings)
    val exporter = RecipeExporter(context, images)
    val fileImporter = RecipeFileImporter(context, recipes, images, importer)
    val auth = AuthManager(context)
    val backup = BackupRepository(auth, database.recipeDao(), settings)
}

class RecipesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container

        // Back up shortly after every change while signed in with auto-backup on.
        c.appScope.launch {
            c.recipes.changes.collect {
                if (c.auth.currentUid != null && c.settings.current().autoBackup) BackupWorker.enqueueSoon(this@RecipesApp)
            }
        }
        // Keep a periodic sync (pulls changes from other devices) while signed in.
        c.appScope.launch {
            c.auth.account.map { it?.uid }.distinctUntilChanged().collect { uid ->
                if (uid != null && c.settings.current().autoBackup) {
                    BackupWorker.schedulePeriodic(this@RecipesApp)
                    BackupWorker.enqueueSoon(this@RecipesApp)
                } else {
                    BackupWorker.cancelPeriodic(this@RecipesApp)
                }
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as RecipesApp).container
