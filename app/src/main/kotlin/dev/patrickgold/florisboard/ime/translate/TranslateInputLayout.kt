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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.translationManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * In-keyboard translation panel.
 *
 * Reads the sentence being written straight out of the editor, translates it **fully on-device**
 * with the bundled OPUS-MT models, and offers to replace the field content with the result or copy
 * it to the (long-term) clipboard. Direction is auto-detected from the script and can be flipped.
 *
 * Styling deliberately reuses the registered clipboard-panel Snygg elements rather than
 * introducing new theme elements: every bundled and user stylesheet already defines them, so the
 * panel inherits the active theme (including Golden Dark) with no schema migration.
 */
@Composable
fun TranslateInputLayout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val clipboardManager by context.clipboardManager()
    val translationManager by context.translationManager()

    val state by translationManager.state.collectAsState()
    val direction by translationManager.direction.collectAsState()
    val content by editorInstance.activeContentFlow.collectAsState()

    // The editor content window is bounded (~256 chars before the cursor), which in practice is
    // exactly the sentence the user is composing — the right unit to translate.
    val sourceText = remember(content) { (content.textBeforeSelection + content.selectedText).trim() }

    LaunchedEffect(sourceText) {
        translationManager.autoSelectDirection(sourceText)
    }

    val result = (state as? TranslationState.Success)?.text
    val isBusy = state is TranslationState.Translating || state is TranslationState.Preparing

    SnyggColumn(FlorisImeUi.ClipboardContent.elementName, modifier = modifier.fillMaxWidth()) {
        SnyggRow(
            FlorisImeUi.ClipboardHeader.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SnyggIconButton(
                FlorisImeUi.ClipboardHeaderButton.elementName,
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
            SnyggText(
                FlorisImeUi.ClipboardHeaderText.elementName,
                text = stringRes(
                    R.string.translate__direction_label,
                    "source" to stringRes(languageLabelOf(direction.sourceLanguage)),
                    "target" to stringRes(languageLabelOf(direction.targetLanguage)),
                ),
            )
            SnyggIconButton(
                FlorisImeUi.ClipboardHeaderButton.elementName,
                onClick = { translationManager.swapDirection() },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                )
            }
        }

        SnyggBox(
            FlorisImeUi.ClipboardItem.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            SnyggText(
                FlorisImeUi.ClipboardItemDescription.elementName,
                text = sourceText.ifBlank { stringRes(R.string.translate__empty_source) },
            )
        }

        SnyggBox(
            FlorisImeUi.ClipboardItem.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(FlorisImeSizing.keyboardUiHeight() / 3),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val resultText = when (val current = state) {
                    is TranslationState.Idle -> stringRes(R.string.translate__hint_press_translate)
                    is TranslationState.Preparing -> stringRes(R.string.translate__preparing_model)
                    is TranslationState.Translating -> stringRes(R.string.translate__translating)
                    is TranslationState.Success -> current.text
                    is TranslationState.Failure -> stringRes(current.messageId)
                }
                SnyggText(FlorisImeUi.ClipboardItemDescription.elementName, text = resultText)
            }
        }

        SnyggRow(
            FlorisImeUi.ClipboardItemActions.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggButton(
                FlorisImeUi.ClipboardClearAllDialogButton.elementName,
                onClick = { translationManager.translate(sourceText) },
                enabled = sourceText.isNotBlank() && !isBusy,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        modifier = Modifier.padding(end = 4.dp),
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                    )
                    SnyggText(
                        FlorisImeUi.ClipboardItemActionText.elementName,
                        text = stringRes(R.string.translate__action_translate),
                    )
                }
            }
            SnyggButton(
                FlorisImeUi.ClipboardClearAllDialogButton.elementName,
                onClick = {
                    val text = result ?: return@SnyggButton
                    // Replace the whole field: select everything, then commit over the selection.
                    editorInstance.performClipboardSelectAll()
                    editorInstance.commitText(text)
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                },
                enabled = result != null,
            ) {
                SnyggText(
                    FlorisImeUi.ClipboardItemActionText.elementName,
                    text = stringRes(R.string.translate__action_replace),
                )
            }
            SnyggButton(
                FlorisImeUi.ClipboardClearAllDialogButton.elementName,
                onClick = { result?.let { clipboardManager.addNewPlaintext(it) } },
                enabled = result != null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        modifier = Modifier.padding(end = 4.dp),
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                    )
                    SnyggText(
                        FlorisImeUi.ClipboardItemActionText.elementName,
                        text = stringRes(R.string.translate__action_copy),
                    )
                }
            }
        }
    }
}

private fun languageLabelOf(language: String): Int = when (language) {
    "ar" -> R.string.translate__language_arabic
    else -> R.string.translate__language_english
}
