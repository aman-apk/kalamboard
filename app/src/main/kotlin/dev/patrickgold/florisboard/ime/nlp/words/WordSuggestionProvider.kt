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

package dev.patrickgold.florisboard.ime.nlp.words

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The unified word-suggestion engine for all alphabetic languages (currently shipping dictionaries:
 * Arabic and English). Replaces the former stub `LatinLanguageProvider`; the provider id is kept
 * unchanged on purpose, because every subtype (including the Arabic preset) already defaults to it
 * — see `Subtype.kt` `SubtypeNlpProviderMap`.
 *
 * Behavior per keystroke (see [suggest]):
 * - while composing a word: exact matches, then prefix completions, then proximity-weighted fuzzy
 *   corrections from [WordIndex], all ranked by frequency;
 * - right after committing a word (empty composing region): next-word predictions from the
 *   on-disk bigram table ([SqliteWordDictionary.nextWords]) — this is how multi-word phrases like
 *   "يعطيك العافية" chain themselves word by word;
 * - autocorrect: when the composing word is NOT a known word and a close high-confidence
 *   correction exists, that correction is flagged [SuggestionCandidate.isEligibleForAutoCommit],
 *   which the existing commit logic in `KeyboardManager` applies on space/punctuation. Gated
 *   behind the `correction__auto_correct_enabled` pref (toggleable via the autocorrect key).
 */
class WordSuggestionProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Keep this id stable: it is the default for all subtypes (see `ime/core/Subtype.kt`)
        // and is stored inside users' persisted subtype configs.
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        /** Only ever keep this many language dictionaries in memory at the same time. */
        private const val MAX_LOADED_DICTIONARIES = 3

        private const val AUTO_CORRECT_MIN_COMPOSING_LENGTH = 3
        private const val AUTO_CORRECT_MAX_DISTANCE = 1.0
        private const val AUTO_CORRECT_MIN_FREQ = 40
        private const val AUTO_CORRECT_MIN_SCORE_MARGIN = 1.5
    }

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore

    private val dictionariesGuard = Mutex()
    /** language code -> dictionary; iteration order = load order (used for eviction). */
    private val dictionaries = LinkedHashMap<String, SqliteWordDictionary>()

    override val providerId = ProviderId

    override suspend fun create() {
        // No language-independent setup needed.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        val language = subtype.primaryLocale.language
        dictionariesGuard.withLock {
            if (dictionaries.containsKey(language)) return@withLock
            val dictionary = SqliteWordDictionary.load(
                context = appContext,
                language = language,
                normalizer = WordNormalizer.forLanguage(language),
            ) ?: return@withLock // No dictionary asset for this language: suggest() stays empty.
            dictionaries[language] = dictionary
            while (dictionaries.size > MAX_LOADED_DICTIONARIES) {
                val eldest = dictionaries.entries.first()
                dictionaries.remove(eldest.key)
                eldest.value.close()
            }
        }
    }

    private suspend fun dictionaryFor(subtype: Subtype): SqliteWordDictionary? {
        val language = subtype.primaryLocale.language
        return dictionariesGuard.withLock { dictionaries[language] }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val dictionary = dictionaryFor(subtype) ?: return emptyList()
        val normalizer = WordNormalizer.forLanguage(dictionary.language)
        val composing = content.composingText

        if (composing.isBlank()) {
            return suggestNextWords(dictionary, normalizer, content, maxCandidateCount)
        }

        val query = normalizer.normalize(composing)
        if (query.isEmpty()) return emptyList()
        val ranked = dictionary.index.suggest(query, maxCandidateCount, allowPossiblyOffensive)
        if (ranked.isEmpty()) return emptyList()

        val hasExactMatch = ranked.first().isExactMatch
        val autoCorrectEnabled = prefs.correction.autoCorrectEnabled.get()
        val top = ranked.first()
        val second = ranked.getOrNull(1)
        val autoCommitTop = autoCorrectEnabled &&
            !hasExactMatch &&
            top.isCorrection &&
            composing.length >= AUTO_CORRECT_MIN_COMPOSING_LENGTH &&
            top.distance <= AUTO_CORRECT_MAX_DISTANCE &&
            top.entry.freq >= AUTO_CORRECT_MIN_FREQ &&
            (second == null || top.score >= second.score * AUTO_CORRECT_MIN_SCORE_MARGIN)

        return ranked.mapIndexed { i, rankedWord ->
            WordSuggestionCandidate(
                text = rankedWord.entry.word,
                secondaryText = null,
                confidence = when {
                    rankedWord.isExactMatch -> 1.0
                    else -> (rankedWord.entry.freq / 255.0).coerceIn(0.0, 1.0) * 0.9
                },
                isEligibleForAutoCommit = i == 0 && autoCommitTop,
                sourceProvider = this,
            )
        }
    }

    /** Next-word prediction from the bigram table, driven by the last committed word. */
    private fun suggestNextWords(
        dictionary: SqliteWordDictionary,
        normalizer: WordNormalizer,
        content: EditorContent,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        val previousWord = extractLastWord(content.textBeforeSelection)
        if (previousWord.isEmpty()) return emptyList()
        val prevNorm = normalizer.normalize(previousWord)
        return dictionary.nextWords(prevNorm, maxCandidateCount).map { (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                secondaryText = null,
                confidence = (freq / 255.0).coerceIn(0.0, 1.0),
                isEligibleForAutoCommit = false,
                sourceProvider = this,
            )
        }
    }

    /** Extracts the trailing word (last run of letters) from the text before the cursor. */
    private fun extractLastWord(textBeforeSelection: CharSequence): String {
        val text = textBeforeSelection.trimEnd()
        if (text.isEmpty()) return ""
        var start = text.length
        while (start > 0 && text[start - 1].isLetter()) {
            start--
        }
        return text.substring(start)
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val dictionary = dictionaryFor(subtype) ?: return SpellingResult.unspecified()
        val normalizer = WordNormalizer.forLanguage(dictionary.language)
        val norm = normalizer.normalize(word)
        if (norm.isEmpty() || dictionary.index.exact(norm).isNotEmpty()) {
            return SpellingResult.validWord()
        }
        val corrections = dictionary.index.suggest(norm, maxSuggestionCount, allowPossiblyOffensive)
            .filter { it.isCorrection || it.isExactMatch }
            .map { it.entry.word }
        return if (corrections.isEmpty()) {
            SpellingResult.validWord() // Unknown word, no plausible correction: don't mark as typo.
        } else {
            SpellingResult.typo(corrections.toTypedArray())
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Personal learning lands in phase 4 (user dictionary + personal bigrams).
        flogDebug { "accepted: ${candidate.text}" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Personal learning lands in phase 4 (revert = negative signal for this correction).
        flogDebug { "reverted: ${candidate.text}" }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        // Personal blocklist lands in phase 4.
        flogDebug { "remove requested: ${candidate.text}" }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return dictionaryFor(subtype)?.index?.words() ?: emptyList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val dictionary = dictionaryFor(subtype) ?: return 0.0
        val normalizer = WordNormalizer.forLanguage(dictionary.language)
        return dictionary.index.frequencyOf(word, normalizer) / 255.0
    }

    override suspend fun destroy() {
        dictionariesGuard.withLock {
            dictionaries.values.forEach { it.close() }
            dictionaries.clear()
        }
    }
}
