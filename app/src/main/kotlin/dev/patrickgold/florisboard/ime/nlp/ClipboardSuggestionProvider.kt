/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.lib.util.NetworkUtils

private val BlankStrRegex = Regex("^\\s*$")

/**
 * Suggestion provider for clipboard chips: when the clipboard holds a fresh item and the editor
 * is empty, it offers the item (plus any e-mail/URL/phone fragments found inside a text item) as
 * one-tap candidates. Extracted from `NlpManager` (phase 6) where it lived as an inner class.
 */
class ClipboardSuggestionProvider(private val context: Context) : SuggestionProvider {
    private val prefs by FlorisPreferenceStore
    private val clipboardManager by context.clipboardManager()

    private var lastClipboardItemId: Long = -1

    override val providerId = "org.florisboard.nlp.providers.clipboard"

    override suspend fun create() {
        // Do nothing
    }

    override suspend fun preload(subtype: Subtype) {
        // Do nothing
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        // Check if enabled
        if (!prefs.clipboard.suggestionEnabled.get()) return emptyList()

        val currentItem = validateClipboardItem(clipboardManager.primaryClip, lastClipboardItemId, content.text)
            ?: return emptyList()

        return buildList {
            val now = System.currentTimeMillis()
            if ((now - currentItem.creationTimestampMs) < prefs.clipboard.suggestionTimeout.get() * 1000) {
                add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider, context = context))
                if (currentItem.isSensitive) {
                    return@buildList
                }
                if (currentItem.type == ItemType.TEXT) {
                    val text = currentItem.stringRepresentation()
                    val matches = buildList {
                        addAll(NetworkUtils.getEmailAddresses(text))
                        addAll(NetworkUtils.getUrls(text))
                        addAll(NetworkUtils.getPhoneNumbers(text))
                    }
                    matches.forEachIndexed { i, match ->
                        val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                            prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                        }
                        if (match.value != text && isUniqueMatch) {
                            add(ClipboardSuggestionCandidate(
                                clipboardItem = currentItem.copy(
                                    // TODO: adjust regex of phone number so we don't need to manually strip the
                                    //  parentheses from the match results
                                    text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                        match.value.substring(1, match.value.length - 1)
                                    } else {
                                        match.value
                                    }
                                ),
                                sourceProvider = this@ClipboardSuggestionProvider,
                                context = context,
                            ))
                        }
                    }
                }
            }
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate is ClipboardSuggestionCandidate) {
            lastClipboardItemId = candidate.clipboardItem.id
        }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Do nothing
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        if (candidate is ClipboardSuggestionCandidate) {
            lastClipboardItemId = candidate.clipboardItem.id
            return true
        }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return 0.0
    }

    override suspend fun destroy() {
        // Do nothing
    }

    private fun validateClipboardItem(currentItem: ClipboardItem?, lastItemId: Long, contentText: String) =
        currentItem?.takeIf {
            // Check if already used
            it.id != lastItemId
                // Check if content is empty
                && contentText.isBlank()
                // Check if clipboard content has any valid characters
                && !currentItem.text.isNullOrBlank()
                && !BlankStrRegex.matches(currentItem.text)
        }
}
