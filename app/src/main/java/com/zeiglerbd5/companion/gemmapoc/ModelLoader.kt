package com.zeiglerbd5.companion.gemmapoc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads (once) and loads the Gemma 4 E2B `.litertlm` model, then
 * exposes the ready [Engine]. Mirrors the iOS sibling's `ModelLoader.swift`
 * shape — same state machine, same Engine / EngineConfig API surface, same
 * model file, same R2 hosting.
 *
 * Sideloading still works and skips the download entirely:
 *
 *     adb push gemma-4-E2B-it.litertlm \
 *       /sdcard/Android/data/ai.stillwaterai.onhand/files/
 */
class ModelLoader(application: Application) : AndroidViewModel(application) {

    sealed interface LoadState {
        data object Idle : LoadState
        data object Locating : LoadState
        data class Loading(val message: String) : LoadState
        data class Ready(val engine: Engine) : LoadState
        data class Failed(val message: String) : LoadState
    }

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    /**
     * Numeric snapshot of the first-launch download. Null when no download
     * is in progress (cached file already present, or download finished).
     * Exposed separately from [state] so the UI can render a real progress
     * bar + byte readout instead of a stringified status.
     */
    data class DownloadProgress(
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        /** 0.0–1.0, or 0.0 when the server didn't send Content-Length. */
        val fractionCompleted: Double
            get() = if (bytesTotal > 0) bytesDone.toDouble() / bytesTotal else 0.0
    }

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    /**
     * App-private external-storage path for the model file. Also the
     * `adb push` sideload target. No runtime permission needed on API 31+.
     */
    fun modelFile(): File {
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: error("External files dir not available (storage unmounted?)")
        return File(dir, MODEL_FILENAME)
    }

    /**
     * Whether the model file already exists locally. Distinguishes "first
     * launch, need consent to download" from "repeat launch, safe to
     * auto-open a local file". Play requires (and App Store guideline
     * 4.2.3(ii) enforces) explicit consent before a multi-GB download.
     */
    fun isModelCached(): Boolean = modelFile().exists()

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Locating

            val file = modelFile()
            try {
                withContext(Dispatchers.IO) { ensureModelDownloaded(file) }
            } catch (t: Throwable) {
                _downloadProgress.value = null
                _state.value = LoadState.Failed("Download failed: ${t.message}")
                return@launch
            }

            _state.value = LoadState.Loading("Loading model into LiteRT-LM…")

            // initialize() is blocking and can take ~10s — keep it off the
            // main thread. createConversation() is synchronous but called
            // from PromptRunner later, not here.
            try {
                val engine = withContext(Dispatchers.IO) {
                    val cacheDir = getApplication<Application>().cacheDir.apply { mkdirs() }
                    val config = EngineConfig(
                        modelPath = file.absolutePath,
                        cacheDir = cacheDir.absolutePath,
                    )
                    Engine(config).also { it.initialize() }
                }
                _state.value = LoadState.Ready(engine)
            } catch (t: Throwable) {
                _state.value = LoadState.Failed("Engine init failed: ${t.message}\n\n$t")
            }
        }
    }

    /**
     * Streams the model from R2 to [dest] if it isn't already there,
     * publishing progress from the read loop. A manual
     * HttpURLConnection read loop counts bytes itself, so progress can't
     * silently stop firing (the iOS async-download API had exactly that
     * bug and had to drop to a delegate-based download task). Writes to a
     * `.part` file and renames on success so a killed download never
     * passes the [isModelCached] check.
     */
    private fun ensureModelDownloaded(dest: File) {
        if (dest.exists()) return

        val part = File(dest.parentFile, dest.name + ".part")
        part.delete()

        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code downloading model")

            val total = conn.contentLengthLong // -1 when the server omits it
            _downloadProgress.value = DownloadProgress(0L, total)

            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        _downloadProgress.value = DownloadProgress(done, total)
                    }
                }
            }

            if (!part.renameTo(dest)) {
                throw java.io.IOException("Could not move downloaded file into place")
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            conn.disconnect()
            _downloadProgress.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        (state.value as? LoadState.Ready)?.engine?.close()
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        /**
         * Cloudflare R2 public URL (bucket `on-hand-model`). Migrated off
         * HuggingFace after their move to the Xet signed-URL CDN started
         * returning 403 to mobile HTTP clients. R2 serves a static URL —
         * no auth, no expiring signature.
         */
        const val MODEL_URL =
            "https://pub-4b7dd739c5094d23ba564623b197c31c.r2.dev/gemma-4-E2B-it.litertlm"

        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024

        /**
         * "890 MB" below 1 GiB, "2.53 GB" above — matches the iOS readout.
         * Locale.US so the output is stable ("2.53", never "2,53"), same as
         * Swift's non-localized `String(format:)`.
         */
        fun formatBytes(n: Long): String {
            val mb = n / 1_048_576.0
            return if (mb >= 1024) String.format(java.util.Locale.US, "%.2f GB", mb / 1024)
            else String.format(java.util.Locale.US, "%.0f MB", mb)
        }
    }
}
