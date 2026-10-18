package com.stephenjoy.recipes.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.stephenjoy.recipes.ui.detail.RecipeDetailScreen
import com.stephenjoy.recipes.ui.editor.RecipeEditorScreen
import com.stephenjoy.recipes.ui.home.HomeScreen
import com.stephenjoy.recipes.ui.importer.ImportScreen
import com.stephenjoy.recipes.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class DetailRoute(val id: Long)
/** id 0 creates a new recipe. */
@Serializable data class EditorRoute(val id: Long = 0)
@Serializable data class ImportRoute(val url: String)
@Serializable object SettingsRoute

@Composable
fun RecipesNavHost(
    sharedLink: String?,
    onSharedLinkConsumed: () -> Unit,
    sharedFile: Uri? = null,
    onSharedFileConsumed: () -> Unit = {},
    nav: NavHostController = rememberNavController(),
) {
    // Files opened with / shared to the app are imported from the home screen.
    LaunchedEffect(sharedFile) {
        if (sharedFile != null) nav.popBackStack(HomeRoute, inclusive = false)
    }

    LaunchedEffect(sharedLink) {
        if (sharedLink != null) {
            nav.navigate(ImportRoute(sharedLink)) { launchSingleTop = true }
            onSharedLinkConsumed()
        }
    }

    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenRecipe = { nav.navigate(DetailRoute(it)) },
                onNewRecipe = { nav.navigate(EditorRoute()) },
                onImportLink = { nav.navigate(ImportRoute(it)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                incomingFile = sharedFile,
                onIncomingFileConsumed = onSharedFileConsumed,
            )
        }
        composable<DetailRoute> { entry ->
            val route = entry.toRoute<DetailRoute>()
            RecipeDetailScreen(
                recipeId = route.id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(EditorRoute(route.id)) },
            )
        }
        composable<EditorRoute> { entry ->
            val route = entry.toRoute<EditorRoute>()
            RecipeEditorScreen(
                recipeId = route.id,
                onBack = { nav.popBackStack() },
                onSaved = { savedId ->
                    if (route.id == 0L) {
                        nav.navigate(DetailRoute(savedId)) { popUpTo<EditorRoute> { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
            )
        }
        composable<ImportRoute> { entry ->
            val route = entry.toRoute<ImportRoute>()
            ImportScreen(
                url = route.url,
                onBack = { nav.popBackStack() },
                onOpenRecipe = { id ->
                    nav.navigate(DetailRoute(id)) { popUpTo<ImportRoute> { inclusive = true } }
                },
                onEditRecipe = { id ->
                    nav.navigate(DetailRoute(id)) { popUpTo<ImportRoute> { inclusive = true } }
                    nav.navigate(EditorRoute(id))
                },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
