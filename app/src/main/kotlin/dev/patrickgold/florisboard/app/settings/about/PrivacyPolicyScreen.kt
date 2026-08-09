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

package dev.patrickgold.florisboard.app.settings.about

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.io.FlorisRef
import dev.patrickgold.florisboard.lib.io.loadTextAsset
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.stringRes

/**
 * Shows the Aman Labs privacy policy from a bundled asset — fully offline, consistent with the
 * app having no Internet permission (an external link would have nowhere to open anyway).
 * The asset path is a translatable resource so each app language loads its own text.
 */
@Composable
fun PrivacyPolicyScreen() = FlorisScreen {
    title = stringRes(R.string.about__privacy_policy__title)
    scrollable = false

    val context = LocalContext.current

    content {
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .florisVerticalScroll(),
        ) {
            val policyText = FlorisRef.assets(stringRes(R.string.privacy_policy__asset_path))
                .loadTextAsset(context)
                .getOrElse { "" }
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = policyText,
                fontSize = 14.sp,
            )
        }
    }
}
