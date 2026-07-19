package com.zeiglerbd5.companion.gemmapoc

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads (once) and loads the Gemma 4 E2B `.litertlm` model, then
 * exposes the ready [Engine]. Mirrors the iOS sibling's `ModelLoader.swift`
 * shape — same state machine, same Engine / EngineConfig API surface, same
 * model file, same R2 hosting.
 *
 * The download goes through Android's [DownloadManager] system service, not
 * an in-process HTTP client: a 2.6 GB pull takes 5–20 minutes, and an
 * in-process download dies the moment the user backgrounds the app or the
 * screen locks (the exact bug the iOS build shipped with). DownloadManager
 * survives app suspension and death, retries on network drops, and shows a
 * system progress notification. If the app is killed mid-download, the next
 * launch reattaches to the in-flight download instead of restarting it.
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
        data class Failed(
            val message: String,
            /**
             * True when the engine rejected an existing model file —
             * usually a damaged download. Enables the "delete model and
             * re-download" recovery path: plain Retry would skip the
             * download (file exists) and hit the same error forever.
             */
            val modelMayBeCorrupt: Boolean = false,
        ) : LoadState
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

    private val downloadManager: DownloadManager
        get() = getApplication<Application>()
            .getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val prefs
        get() = getApplication<Application>()
            .getSharedPreferences("model_download", Context.MODE_PRIVATE)

    /**
     * App-private external-storage path for the model file. Also the
     * `adb push` sideload target. No runtime permission needed on API 31+.
     */
    fun modelFile(): File {
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: error("External files dir not available (storage unmounted?)")
        return File(dir, MODEL_FILENAME)
    }

    /** DownloadManager writes here; renamed to [modelFile] on completion. */
    private fun partFile(): File = File(modelFile().parentFile, "$MODEL_FILENAME.part")

    /**
     * Whether the model file already exists locally. Distinguishes "first
     * launch, need consent to download" from "repeat launch, safe to
     * auto-open a local file". Play requires (and App Store guideline
     * 4.2.3(ii) enforces) explicit consent before a multi-GB download.
     */
    fun isModelCached(): Boolean = modelFile().exists()

    /**
     * Whether a DownloadManager job from a previous app run is still
     * running (or finished while we were dead). Lets a relaunch mid-download
     * reattach — showing progress — instead of re-asking for consent.
     */
    fun hasActiveDownload(): Boolean {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id == -1L) return false
        val status = queryStatus(id)?.first
        return status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED ||
            status == DownloadManager.STATUS_SUCCESSFUL
    }

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
                _state.value = LoadState.Failed(
                    "Engine init failed: ${t.message}",
                    modelMayBeCorrupt = true,
                )
            }
        }
    }

    /**
     * Ensures the model file exists locally, downloading via DownloadManager
     * if needed. Reuses an in-flight download from a previous app run when
     * one exists; otherwise enqueues a fresh one. Suspends (polling status +
     * progress) until the file is in place or the download fails.
     */
    private suspend fun ensureModelDownloaded(dest: File) {
        if (dest.exists()) return

        var id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val activeStates = setOf(
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_SUCCESSFUL,
        )
        // A download that DownloadManager thinks is alive but whose .part
        // file is gone would resume mid-file into a recreated sparse file —
        // correct size, zero-filled head, "invalid magic number" at engine
        // init. Cancel it and start clean instead of reattaching.
        if (id != -1L && queryStatus(id)?.first in activeStates && !partFile().exists()) {
            downloadManager.remove(id)
            prefs.edit { remove(PREF_DOWNLOAD_ID) }
            id = -1L
        }
        if (id == -1L || queryStatus(id)?.first !in activeStates) {
            partFile().delete()
            val request = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("OnHand_AI model")
                .setDescription("One-time AI model download")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    getApplication(), null, partFile().name)
            id = downloadManager.enqueue(request)
            prefs.edit { putLong(PREF_DOWNLOAD_ID, id) }
        }

        while (true) {
            val (status, reason) = queryStatus(id)
                ?: throw IllegalStateException(
                    "Download disappeared (cancelled from notification?)")
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit { remove(PREF_DOWNLOAD_ID) }
                    _downloadProgress.value = null
                    if (!partFile().renameTo(dest)) {
                        partFile().delete()
                        throw IllegalStateException(
                            "Could not move downloaded file into place")
                    }
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadManager.remove(id)
                    prefs.edit { remove(PREF_DOWNLOAD_ID) }
                    _downloadProgress.value = null
                    throw IllegalStateException(failureMessage(reason))
                }
                else -> {
                    queryProgress(id)?.let { _downloadProgress.value = it }
                    delay(PROGRESS_POLL_MS)
                }
            }
        }
    }

    /** (status, reason) for a DownloadManager id, or null if unknown. */
    private fun queryStatus(id: Long): Pair<Int, Int>? =
        downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            Pair(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }

    private fun queryProgress(id: Long): DownloadProgress? =
        downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            DownloadProgress(
                bytesDone = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                bytesTotal = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    /**
     * Deletes the (presumed damaged) model file plus any in-flight
     * download and returns to Idle, so the consent card shows and the
     * user can re-download from scratch. The escape hatch for a model
     * file the engine refuses to load.
     */
    fun deleteModelAndReset() {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id != -1L) downloadManager.remove(id)
        prefs.edit { remove(PREF_DOWNLOAD_ID) }
        partFile().delete()
        modelFile().delete()
        _downloadProgress.value = null
        _state.value = LoadState.Idle
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

        private const val PREF_DOWNLOAD_ID = "download_id"
        private const val PROGRESS_POLL_MS = 500L

        /** Human-readable text for DownloadManager failure reasons. */
        fun failureMessage(reason: Int): String = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE ->
                "Not enough storage space. About 3 GB free is required."
            DownloadManager.ERROR_CANNOT_RESUME ->
                "Download could not resume after interruption. Please retry."
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
                "Network error during download. Please retry."
            else -> "Download failed (code $reason). Please retry."
        }

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
