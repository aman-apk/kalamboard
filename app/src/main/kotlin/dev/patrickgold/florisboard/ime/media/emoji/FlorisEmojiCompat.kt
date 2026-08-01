/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.media.emoji

import android.annotation.SuppressLint
import android.content.Context
import androidx.emoji2.text.EmojiCompat
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Helper object which manages two separate EmojiCompat instances, something EmojiCompat by default does not want us
 * to do for unknown reasons. Additionally we implement a proper loaded callback and a state flow, so the UI can always
 * receive the EmojiCompat instance as soon as it is loaded.
 *
 * OFFLINE BUILD: upstream used `DefaultEmojiCompatConfig.create()` here, which resolves to the Google Play Services
 * *downloadable font provider*. Even though FlorisBoard itself holds no INTERNET permission, that config makes the
 * GMS process fetch the emoji font over the network on behalf of this app — the one remaining indirect network path.
 * This build therefore uses no EmojiCompat config at all: [InstanceHandler.config] is always null, the published
 * instance flow stays null, and every consumer falls back to rendering emoji with the system font (the same fallback
 * the palette logic already implements for GMS-less/AOSP devices).
 *
 * If full emoji coverage on old Android versions is ever required, the offline-safe way to restore it is to add the
 * `androidx.emoji2:emoji2-bundled` artifact (ships the Noto Color Emoji font inside the APK, ~10 MB) and build a
 * `BundledEmojiCompatConfig` below — never `DefaultEmojiCompatConfig`.
 *
 * TODO: investigate if having two instances of EmojiCompat has significant memory impact. Based on the docs one
 *  instance has ~300kB, so two should have ~600kB, which should not cause issues.
 *
 * TODO: investigate if having two instances of EmojiCompat causes other logic issues or if there's a better way of
 *  achieving the same result than the current implementation does.
 */
object FlorisEmojiCompat {
    private lateinit var instanceNoReplace: InstanceHandler
    private lateinit var instanceReplaceAll: InstanceHandler

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Initialize this helper and its EmojiCompat instances with given [context]. Immediately begins loading the emoji
     * metadata in a background thread. After this method has been called, it is safe to call [getAsFlow].
     */
    fun init(context: Context) {
        instanceNoReplace = InstanceHandler(context, replaceAll = false)
        instanceReplaceAll = InstanceHandler(context, replaceAll = true)

        scope.launch {
            instanceNoReplace.load()
        }
        scope.launch {
            instanceReplaceAll.load()
        }
    }

    /**
     * Gets the current EmojiCompat instance based on [replaceAll] and sets it as the default instance if
     * [setAsDefaultInstance] is true. Calling this method before [init] will cause an exception to be thrown.
     *
     * @return A state flow providing the latest EmojiCompat instance for given args. The flow may provide null if
     *  EmojiCompat is still loading or if it has failed.
     */
    @SuppressLint("RestrictedApi")
    fun getAsFlow(replaceAll: Boolean, setAsDefaultInstance: Boolean = true): StateFlow<EmojiCompat?> {
        val instanceFlow = if (replaceAll) {
            instanceReplaceAll.publishedInstanceFlow
        } else {
            instanceNoReplace.publishedInstanceFlow
        }
        val instance = instanceFlow.value
        if (setAsDefaultInstance && instance != null) {
            flogInfo { "Set default EmojiCompat instance to $instance(replaceAll=$replaceAll)" }
            // This API is not really supposed to be used by third-party apps, but it is really handy and does
            // exactly what we need, so we suppress the restriction here
            EmojiCompat.reset(instance)
        }
        return instanceFlow
    }

    // `context` is retained because the documented offline alternative, BundledEmojiCompatConfig,
    // needs it; it is unused while the config below stays null.
    @Suppress("UNUSED_PARAMETER")
    private class InstanceHandler(context: Context, replaceAll: Boolean = false) {
        @Suppress("unused") // kept as the wiring for the offline BundledEmojiCompatConfig path, see class KDoc
        private val initCallback: EmojiCompat.InitCallback = object : EmojiCompat.InitCallback() {
            override fun onInitialized() {
                super.onInitialized()
                flogInfo { "EmojiCompat(replaceAll=$replaceAll) successfully loaded!" }
                publishedInstanceFlow.value = instance
            }

            override fun onFailed(throwable: Throwable?) {
                super.onFailed(throwable)
                flogError { "EmojiCompat(replaceAll=$replaceAll) failed to load: $throwable" }
            }
        }

        // OFFLINE BUILD: intentionally null. Upstream called DefaultEmojiCompatConfig.create(context) here, which
        // binds the Google Play Services downloadable font provider and makes GMS fetch the emoji font from the
        // network. With a null config no EmojiCompat instance is created and emoji render via the system font.
        private val config: EmojiCompat.Config? = null

        // Despite its name, `EmojiCompat.reset()` actually creates a new instance, exactly what we need
        private val instance: EmojiCompat? = if (config != null) EmojiCompat.reset(config) else null
        val publishedInstanceFlow = MutableStateFlow<EmojiCompat?>(null)

        /**
         * Manually loads the EmojiCompat instance. Call this method on a background thread to avoid blocking main.
         *
         * @see EmojiCompat.load
         */
        fun load() {
            instance?.load()
        }
    }
}
