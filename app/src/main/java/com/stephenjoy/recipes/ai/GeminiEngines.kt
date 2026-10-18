package com.stephenjoy.recipes.ai

import android.content.Context
import android.os.Build
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** State of the on-device Gemini Nano model managed by Android AICore. */
sealed interface NanoStatus {
    data object Checking : NanoStatus
    data object Unavailable : NanoStatus
    data object Downloadable : NanoStatus
    data class Downloading(val bytes: Long = 0) : NanoStatus
    data object Available : NanoStatus
    data class Failed(val message: String) : NanoStatus
}

/**
 * Gemini Nano, the model built into Android (Pixel 9+, Galaxy S25+ and other AICore devices),
 * accessed through the ML Kit GenAI Prompt API. Runs fully on device.
 */
class OnDeviceGemini {

    private val model by lazy { Generation.getClient() }

    /** Input budget: Nano accepts roughly 4k tokens, so keep prompts compact. */
    val maxInputChars = 9_000

    /** AICore (and so Gemini Nano) only exists on newer Android; never touch ML Kit GenAI below this. */
    private val supportedSdk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    suspend fun status(): NanoStatus = if (!supportedSdk) NanoStatus.Unavailable else runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.Available
            FeatureStatus.DOWNLOADABLE -> NanoStatus.Downloadable
            FeatureStatus.DOWNLOADING -> NanoStatus.Downloading()
            else -> NanoStatus.Unavailable
        }
    }.getOrElse { NanoStatus.Unavailable }

    suspend fun isAvailable(): Boolean = status() == NanoStatus.Available

    fun download(): Flow<NanoStatus> = if (!supportedSdk) flowOf(NanoStatus.Unavailable) else model.download().map { status ->
        when (status) {
            is DownloadStatus.DownloadStarted -> NanoStatus.Downloading()
            is DownloadStatus.DownloadProgress -> NanoStatus.Downloading(status.totalBytesDownloaded)
            is DownloadStatus.DownloadFailed -> NanoStatus.Failed(status.e.message ?: "Download failed")
            else -> NanoStatus.Available
        }
    }

    suspend fun generate(prompt: String): String {
        check(supportedSdk) { "Gemini Nano isn't available on this Android version" }
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                topK = 16
            },
        )
        return response.candidates.firstOrNull()?.text.orEmpty()
    }
}

/**
 * Gemini in the cloud through Firebase AI Logic. Used when Nano isn't on the device or when a
 * video has to be watched (Gemini can read YouTube URLs directly).
 */
class CloudGemini(private val context: Context) {

    fun isConfigured(): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    val maxInputChars = 60_000

    /** [video] is sent inline (e.g. an Instagram reel); [youtubeUrl] lets Gemini fetch YouTube itself. */
    suspend fun generate(prompt: String, modelName: String, youtubeUrl: String? = null, video: ByteArray? = null): String {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                responseMimeType = "application/json"
            },
        )
        val request = content {
            if (youtubeUrl != null) fileData(youtubeUrl, "video/mp4")
            if (video != null) inlineData(video, "video/mp4")
            text(prompt)
        }
        return model.generateContent(request).text.orEmpty()
    }
}

/** Picks Gemini Nano or cloud Gemini according to the user's setting, for small helper prompts. */
class GeminiRouter(
    private val nano: OnDeviceGemini,
    private val cloud: CloudGemini,
    private val settings: com.stephenjoy.recipes.data.SettingsRepository,
) {
    suspend fun isAvailable(): Boolean = engineOrNull() != null

    suspend fun generate(prompt: String): String {
        val prefs = settings.current()
        return when (engineOrNull()) {
            Engine.NANO -> runCatching { nano.generate(prompt) }.getOrElse { e ->
                if (prefs.aiEngine == com.stephenjoy.recipes.data.AiEngine.AUTO && cloud.isConfigured()) {
                    cloud.generate(prompt, prefs.cloudModel)
                } else throw e
            }
            Engine.CLOUD -> cloud.generate(prompt, prefs.cloudModel)
            null -> error("No Gemini model available. Check Settings → AI.")
        }
    }

    private enum class Engine { NANO, CLOUD }

    private suspend fun engineOrNull(): Engine? {
        val engine = settings.current().aiEngine
        return when {
            engine != com.stephenjoy.recipes.data.AiEngine.CLOUD && nano.isAvailable() -> Engine.NANO
            engine != com.stephenjoy.recipes.data.AiEngine.ON_DEVICE && cloud.isConfigured() -> Engine.CLOUD
            else -> null
        }
    }
}
