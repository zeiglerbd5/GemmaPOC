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

/**
 * Loads the Gemma 4 E2B `.litertlm` model and exposes the ready [Engine].
 * Mirrors the iOS sibling's `ModelLoader.swift` shape — same state machine,
 * same Engine / EngineConfig API surface, same model file.
 *
 * First-pass: sideload only. The iOS sibling has a `#if DEBUG` HF-download
 * path; an Android equivalent is a follow-up once smoke-test inference
 * proves out. To install the model on emulator or device:
 *
 *     adb push gemma-4-E2B-it.litertlm \
 *       /sdcard/Android/data/com.zeiglerbd5.companion.gemmapoc/files/
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
     * App-private external-storage path the user `adb push`es the model to.
     * No runtime permission needed on API 31+.
     */
    fun modelFile(): File {
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: error("External files dir not available (storage unmounted?)")
        return File(dir, MODEL_FILENAME)
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Locating

            val file = modelFile()
            if (!file.exists()) {
                _state.value = LoadState.Failed(
                    """
                    Model not found at:
                    ${file.absolutePath}

                    Install with:
                      adb push gemma-4-E2B-it.litertlm \
                        ${file.parent}/
                    """.trimIndent()
                )
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

    override fun onCleared() {
        super.onCleared()
        (state.value as? LoadState.Ready)?.engine?.close()
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    }
}
