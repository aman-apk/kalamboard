/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.translate

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI-facing state of the translation panel. */
sealed interface TranslationState {
    data object Idle : TranslationState
    /** Model files are being staged out of the APK (first use only) or loaded into memory. */
    data object Preparing : TranslationState
    data object Translating : TranslationState
    data class Success(val text: String) : TranslationState
    data class Failure(val messageId: Int) : TranslationState
}

/**
 * Owns the on-device translation engines and drives the translation panel.
 *
 * Everything runs locally: the quantized OPUS-MT models ship inside the APK under
 * `assets/translate/<direction>/` and are staged to the app's private files dir on first use
 * (ONNX Runtime cannot mmap directly out of the compressed APK). Nothing is ever downloaded —
 * the offline build guard would fail the build if a networking dependency crept in.
 *
 * A single engine is kept in memory at a time (each direction is ~107 MB of weights); switching
 * direction swaps it. Engines are released when the keyboard goes away via [releaseEngines].
 */
class TranslationManager(context: Context) {
    private val appContext by context.appContext()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val state = _state.asStateFlow()

    private val _direction = MutableStateFlow(TranslationDirection.AR_TO_EN)
    val direction = _direction.asStateFlow()

    private val engineLock = Any()
    private var loadedDirection: TranslationDirection? = null
    private var engine: OnnxTranslationEngine? = null
    private var activeJob: Job? = null

    fun setDirection(newDirection: TranslationDirection) {
        if (_direction.value == newDirection) return
        _direction.value = newDirection
        _state.value = TranslationState.Idle
    }

    fun swapDirection() = setDirection(_direction.value.reversed())

    /** Picks the direction implied by [text]'s script, unless the user changed it manually. */
    fun autoSelectDirection(text: String) {
        if (text.isBlank()) return
        setDirection(TranslationDirection.detect(text))
    }

    fun clear() {
        activeJob?.cancel()
        _state.value = TranslationState.Idle
    }

    /** Translates [text] in the current direction, replacing any in-flight request. */
    fun translate(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.value = TranslationState.Idle
            return
        }
        activeJob?.cancel()
        val requestedDirection = _direction.value
        activeJob = scope.launch {
            _state.value = TranslationState.Preparing
            val activeEngine = runCatching { obtainEngine(requestedDirection) }.getOrElse { error ->
                flogError { "failed to load translation engine: $error" }
                _state.value = TranslationState.Failure(
                    dev.patrickgold.florisboard.R.string.translate__error_model_unavailable
                )
                return@launch
            }
            _state.value = TranslationState.Translating
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    activeEngine.translate(trimmed, isCancelled = { !isActive })
                }
            }.getOrElse { error ->
                flogError { "translation failed: $error" }
                _state.value = TranslationState.Failure(
                    dev.patrickgold.florisboard.R.string.translate__error_failed
                )
                return@launch
            }
            if (!isActive) return@launch
            _state.value = if (result.isBlank()) {
                TranslationState.Failure(dev.patrickgold.florisboard.R.string.translate__error_failed)
            } else {
                TranslationState.Success(result)
            }
        }
    }

    private suspend fun obtainEngine(target: TranslationDirection): OnnxTranslationEngine =
        withContext(Dispatchers.IO) {
            synchronized(engineLock) {
                engine?.takeIf { loadedDirection == target }?.let { return@withContext it }
            }
            val modelDir = stageModelIfNeeded(target)
            val newEngine = OnnxTranslationEngine.open(modelDir)
            synchronized(engineLock) {
                engine?.close()
                engine = newEngine
                loadedDirection = target
            }
            newEngine
        }

    /**
     * Copies the model assets to `filesDir/translate/<direction>/` on first use. Files are
     * refreshed whenever the asset size changes, which is the cheap stand-in for a version check
     * across app updates (same approach as the dictionary staging).
     */
    private fun stageModelIfNeeded(target: TranslationDirection): File {
        val targetDir = File(appContext.filesDir, "translate/${target.id}")
        targetDir.mkdirs()
        for (name in MODEL_FILES) {
            val assetPath = "translate/${target.id}/$name"
            val outFile = File(targetDir, name)
            val assetSize = appContext.assets.openFd(assetPath).use { it.length }
                .takeIf { it > 0 } ?: appContext.assets.open(assetPath).use { it.available().toLong() }
            if (outFile.exists() && outFile.length() == assetSize) continue
            appContext.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            flogInfo { "staged translation asset $assetPath (${assetSize / 1024} KiB)" }
        }
        return targetDir
    }

    /** Frees the loaded model weights; call when the keyboard is destroyed. */
    fun releaseEngines() {
        activeJob?.cancel()
        synchronized(engineLock) {
            engine?.close()
            engine = null
            loadedDirection = null
        }
        _state.value = TranslationState.Idle
    }

    companion object {
        private val MODEL_FILES = listOf("encoder.onnx", "decoder.onnx", "vocab.tsv", "model_config.json")
    }
}
