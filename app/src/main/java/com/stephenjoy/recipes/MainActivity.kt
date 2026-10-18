package com.stephenjoy.recipes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stephenjoy.recipes.data.AppSettings
import com.stephenjoy.recipes.extract.LinkResolver
import com.stephenjoy.recipes.ui.RecipesNavHost
import com.stephenjoy.recipes.ui.theme.RecipesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    /** A link shared into the app that hasn't been handled by the UI yet. */
    private val sharedLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val link by sharedLink.asStateFlow().collectAsStateWithLifecycle()
            RecipesTheme(dynamicColor = settings.dynamicColor) {
                RecipesNavHost(
                    sharedLink = link,
                    onSharedLinkConsumed = { sharedLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        LinkResolver.extractUrl(text)?.let { sharedLink.value = it }
    }
}
