package com.stephenjoy.recipes.extract

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** What we learned from a post's video. */
class VideoClip(
    /** The video itself, when small enough to send to cloud Gemini (inline request limit). */
    val bytes: ByteArray?,
    /** Text recognised on screen across sampled frames (often the ingredient list). */
    val onScreenText: String,
)

/**
 * Downloads a short video (reels/shorts) and reads the text shown in it, on device, with ML Kit
 * text recognition over evenly spaced frames. The raw bytes are kept for cloud Gemini, which can
 * watch and listen to the whole clip.
 */
class VideoAnalyzer(private val context: Context, private val client: OkHttpClient) {

    private val maxDownloadBytes = 60L * 1024 * 1024
    private val maxInlineBytes = 18 * 1024 * 1024 // Firebase AI inline requests are capped near 20 MB
    private val frameCount = 14

    suspend fun analyze(videoUrl: String): VideoClip = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "video-${videoUrl.hashCode()}.mp4")
        try {
            download(videoUrl, file)
            val text = runCatching { readOnScreenText(file) }.getOrDefault("")
            val bytes = if (file.length() <= maxInlineBytes) file.readBytes() else null
            VideoClip(bytes, text)
        } finally {
            file.delete()
        }
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .header("Referer", "https://www.instagram.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Video download failed (HTTP ${response.code})")
            val body = response.body ?: error("Empty video")
            if (body.contentLength() > maxDownloadBytes) error("Video is too large to analyse")
            target.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxDownloadBytes) error("Video is too large to analyse")
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private suspend fun readOnScreenText(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val retriever = MediaMetadataRetriever()
        val lines = LinkedHashSet<String>()
        try {
            retriever.setDataSource(file.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return ""
            for (i in 0 until frameCount) {
                val timeUs = (durationMs * 1000L) * (2 * i + 1) / (2 * frameCount)
                val frame = frameAt(retriever, timeUs) ?: continue
                try {
                    val result = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
                    result.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                        .filter { it.length >= 3 && !it.startsWith("@") }
                        .forEach { lines += it.replace(Regex("\\s+"), " ") }
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            retriever.release()
            recognizer.close()
        }
        // Drop lines that are near-duplicates (same text recognised slightly differently).
        val seen = mutableSetOf<String>()
        return lines.filter { seen.add(it.lowercase().filter(Char::isLetterOrDigit)) }
            .joinToString("\n")
            .take(5_000)
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1080, 1920)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
}
