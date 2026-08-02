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

package dev.patrickgold.florisboard.ime.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.systemGestureExclusion
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/** Height of the always-visible quick resize strip sitting above the smartbar.
 *  User-directed: as thin as possible so it costs virtually no screen space — the whole strip
 *  is still the drag target, and the modal resize editor remains the fallback for precision. */
val ImeQuickResizeBarHeight = 6.dp

/**
 * A always-visible grab bar at the very top of the keyboard for dragging its height up and down.
 *
 * The keyboard already has a full resize editor (four handles, reachable through the resize quick
 * action), but that is modal and hard to discover. This strip reuses the exact same, already
 * tested drag pipeline — [imeWindowResizeHandle] with [ImeWindowResizeHandle.TOP] — and simply
 * keeps one handle on screen permanently so height is a one-gesture adjustment.
 *
 * Hidden when the user turns off `keyboard__show_resize_bar`, and only meaningful for a docked
 * (fixed) window, since a floating window is resized by dragging its own frame.
 */
@Composable
fun ImeQuickResizeBar(modifier: Modifier = Modifier) {
    val prefs by FlorisPreferenceStore
    val showResizeBar by prefs.keyboard.showResizeBar.collectAsState()
    if (!showResizeBar) return

    val windowController = LocalWindowController.current
    val windowSpec by windowController.activeWindowSpec.collectAsState()
    if (windowSpec !is ImeWindowSpec.Fixed) return

    val style = rememberSnyggThemeQuery(FlorisImeUi.WindowResizeHandle.elementName)
    val gripColor by rememberUpdatedState(style.background(default = Color.Gray))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ImeQuickResizeBarHeight)
            .imeWindowResizeHandle(windowController, ImeWindowResizeHandle.TOP)
            .systemGestureExclusion(),
        contentAlignment = Alignment.Center,
    ) {
        val gripShape = remember { androidx.compose.foundation.shape.RoundedCornerShape(50) }
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 2.5.dp)
                .clip(gripShape)
                .background(gripColor),
        )
    }
}
