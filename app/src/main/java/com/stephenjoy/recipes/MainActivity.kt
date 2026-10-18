package com.stephenjoy.recipes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stephenjoy.recipes.data.AppSettings
import com.stephenjoy.recipes.extract.LinkResolver
import com.stephenjoy.recipes.ui.RecipesNavHost
import com.stephenjoy.recipes.ui.theme.RecipesTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A link shared into the app that hasn't been handled by the UI yet. */
    private val sharedLink = MutableStateFlow<String?>(null)

    /** A recipe file opened with / shared to the app. */
    private val sharedFile = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same splash screen on every Android version (the system one only exists on 12+).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val link by sharedLink.collectAsStateWithLifecycle()
            val file by sharedFile.collectAsStateWithLifecycle()
            RecipesTheme(dynamicColor = settings.dynamicColor) {
                RecipesNavHost(
                    sharedLink = link,
                    onSharedLinkConsumed = { sharedLink.value = null },
                    sharedFile = file,
                    onSharedFileConsumed = { sharedFile.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { sharedFile.value = it }
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        streamOf(intent)?.let {
            sharedFile.value = it
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        LinkResolver.extractUrl(text)?.let { sharedLink.value = it }
    }

    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
