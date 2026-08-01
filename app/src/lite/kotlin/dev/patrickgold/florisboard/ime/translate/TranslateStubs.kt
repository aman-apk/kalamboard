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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.stringRes

/**
 * Lite-flavor counterparts of the translation feature.
 *
 * The `lite` build ships neither the ~214 MB of OPUS-MT models nor the ONNX Runtime native
 * libraries (another ~70 MB across ABIs), so the real implementations live in `src/full/kotlin`
 * and these stand-ins keep the shared code (ImeWindow, FlorisApplication) compiling and behaving
 * sanely. The translate quick action is hidden in this flavor, so the panel is unreachable in
 * normal use — the placeholder only exists as a safety net for a stale saved action arrangement.
 */
class TranslationManager(context: Context) {
    /** Nothing to release: no engine is ever loaded in the lite flavor. */
    fun releaseEngines() = Unit
}

@Composable
fun TranslateInputLayout(modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.fillMaxWidth(),
        text = stringRes(R.string.translate__unavailable_in_lite),
    )
}
