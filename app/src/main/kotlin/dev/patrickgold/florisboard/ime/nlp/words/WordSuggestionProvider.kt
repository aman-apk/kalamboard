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
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
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
 *   corrections from [WordIndex] — merged with the user's personal dictionary (boosted), minus the
 *   user's blocklist;
 * - right after committing a word (empty composing region): next-word predictions from the static
 *   bigram table merged with the personal bigrams learned from the user's own typing — this is how
 *   multi-word phrases like "يعطيك العافية" chain themselves word by word;
 * - autocorrect: when the composing word is NOT a known word and a close high-confidence
 *   correction exists, that correction is flagged [SuggestionCandidate.isEligibleForAutoCommit],
 *   which the existing commit logic in `KeyboardManager` applies on space/punctuation. Gated
 *   behind the `correction__auto_correct_enabled` pref and suppressed for words whose correction
 *   the user has repeatedly reverted.
 *
 * On-device learning (see [notifyWordCommitted], all local, none of it in private sessions):
 * - an unknown word typed [PersonalLearning.PENDING_THRESHOLD] times is added to the Floris user
 *   dictionary (visible/editable/exportable in Settings → Dictionary);
 * - a re-typed or accepted user-dictionary word gets its frequency bumped;
 * - every commit also feeds the personal bigram table (`floris_learning` DB) powering
 *   personalized next-word prediction;
 * - backspacing an auto-correction records a negative signal; long-pressing a suggestion away
 *   blocklists it permanently.
 */
class WordSuggestionProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Keep this id stable: it is the default for all subtypes (see `ime/core/Subtype.kt`)
        // and is stored inside users' persisted subtype configs.
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        /** Only ever keep this many language dictionaries in memory at the same time. */
        private const val MAX_LOADED_DICTIONARIES = 3

        /** How many followers of the previous word are fetched for contextual reranking. */
        private const val CONTEXT_FOLLOWER_FETCH = 16

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
    /** language -> overlayKey of the last FAILED load attempt (no asset / broken asset), so a
     *  language without a shipped dictionary doesn't retry asset I/O on every keystroke. */
    private val failedLoads = HashMap<String, String>()

    private val learning by lazy { LearningStore(appContext) }
    private val dictionaryManager get() = DictionaryManager.default()

    /** Small per-language index over the user dictionary; rebuilt lazily after learning writes. */
    private val userIndexGuard = Mutex()
    private var userIndexLanguage: String? = null
    private var userIndex: WordIndex? = null
    @Volatile private var userIndexDirty = true

    override val providerId = ProviderId

    override suspend fun create() {
        learning.decayIfDue(System.currentTimeMillis())
    }

    /** The dialect overlay key the dictionary for [language] should currently be loaded with. */
    private suspend fun wantedOverlayKey(language: String): String {
        if (language != "ar") return ""
        return prefs.suggestion.arabicDialect.get().overlayKey ?: ""
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        val language = subtype.primaryLocale.language
        loadDictionaryIfNeeded(language)
        // Warm the user index for this language as well.
        userIndexFor(language)
        Unit
    }

    private suspend fun loadDictionaryIfNeeded(language: String) {
        val overlayKey = wantedOverlayKey(language)
        dictionariesGuard.withLock {
            val cached = dictionaries[language]
            if (cached != null && cached.overlayKey == overlayKey) return@withLock
            if (failedLoads[language] == overlayKey) return@withLock // known-missing, don't re-stage per keystroke
            val dialect = if (language == "ar") prefs.suggestion.arabicDialect.get() else ArabicDialect.NONE
            val dictionary = SqliteWordDictionary.load(
                context = appContext,
                language = language,
                normalizer = WordNormalizer.forLanguage(language),
                overlay = DialectOverlay.loadFromAssets(appContext, language, dialect),
                overlayKey = overlayKey,
            )
            if (dictionary == null) {
                // No dictionary asset for this language: static suggestions stay empty.
                failedLoads[language] = overlayKey
                return@withLock
            }
            failedLoads.remove(language)
            dictionaries.remove(language)?.close()
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
        val overlayKey = wantedOverlayKey(language)
        dictionariesGuard.withLock { dictionaries[language] }.let { cached ->
            // Reload transparently when the user switched their dialect setting.
            if (cached != null && cached.overlayKey == overlayKey) return cached
        }
        loadDictionaryIfNeeded(language)
        return dictionariesGuard.withLock { dictionaries[language] }
    }

    // --- user dictionary index -------------------------------------------------------------------

    private fun matchesLanguage(localeStr: String?, language: String): Boolean {
        if (localeStr == null) return true
        val tag = localeStr.replace('_', '-').lowercase()
        return tag == language || tag.startsWith("$language-")
    }

    private suspend fun userIndexFor(language: String): WordIndex = userIndexGuard.withLock {
        val cached = userIndex
        if (cached != null && userIndexLanguage == language && !userIndexDirty) return@withLock cached
        val normalizer = WordNormalizer.forLanguage(language)
        val entries = try {
            dictionaryManager.loadUserDictionariesIfNecessary()
            buildList {
                dictionaryManager.florisUserDictionaryDao()?.queryAll()?.let { addAll(it) }
                dictionaryManager.systemUserDictionaryDao()?.queryAll()?.let { addAll(it) }
            }
        } catch (e: Exception) {
            flogError { "failed to read user dictionaries: $e" }
            emptyList()
        }
        val wordEntries = entries.asSequence()
            .filter { matchesLanguage(it.locale, language) }
            .groupBy { it.word }
            .map { (word, group) ->
                WordEntry(
                    word = word,
                    norm = normalizer.normalize(word),
                    freq = group.maxOf { it.freq }.coerceIn(1, 255),
                )
            }
        val index = WordIndex(wordEntries, KeyProximity.forLanguage(language))
        userIndexLanguage = language
        userIndex = index
        userIndexDirty = false
        index
    }

    private fun invalidateUserIndex() {
        userIndexDirty = true
    }

    // --- suggestion ------------------------------------------------------------------------------

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val language = subtype.primaryLocale.language
        val dictionary = dictionaryFor(subtype)
        val normalizer = WordNormalizer.forLanguage(language)
        val composing = content.composingText

        if (composing.isBlank()) {
            return suggestNextWords(language, dictionary, normalizer, content, maxCandidateCount)
        }

        val query = normalizer.normalize(composing)
        if (query.isEmpty()) return emptyList()

        val userIdx = userIndexFor(language)
        var staticRanked = dictionary?.index?.suggest(query, maxCandidateCount * 2, allowPossiblyOffensive)
            ?: emptyList()
        // Fetch user candidates as generously as static ones so fuzzy corrections of the user's
        // own vocabulary are never truncated inside the mini index before the merge.
        var userRanked = userIdx.suggest(query, maxCandidateCount * 2, allowPossiblyOffensive)
        // العربية تُلصق السوابق («واليوم») وتخطئ المسافة بـ«و/ة» («كلمةوحلوة») — انظر ArabicClitics.
        val splitFixes: List<RankedWord>
        if (language == "ar") {
            dictionary?.index?.let {
                staticRanked = staticRanked + ArabicClitics.cliticCandidates(it, composing, query, allowPossiblyOffensive)
            }
            userRanked = userRanked + ArabicClitics.cliticCandidates(userIdx, composing, query, allowPossiblyOffensive)
            splitFixes = ArabicClitics.splitCandidates(
                indexes = listOfNotNull(userIdx, dictionary?.index),
                composing = composing,
                normalizer = normalizer,
                allowPossiblyOffensive = allowPossiblyOffensive,
            )
        } else {
            splitFixes = emptyList()
        }
        val merged = ArabicClitics.seedSplit(
            PersonalLearning.mergeRanked(
                static = staticRanked,
                user = userRanked,
                blocked = learning.blockedWords(language),
                maxCount = maxCandidateCount,
            ),
            splits = splitFixes,
            maxCount = maxCandidateCount,
        )
        if (merged.isEmpty()) return emptyList()

        // Contextual reranking: what usually FOLLOWS the preceding words wins ties and close calls
        // among corrections/completions ("صباح الخ" -> الخير even if a stray match scores higher).
        // The two-word (trigram) context, when available, outweighs the one-word (bigram) one.
        val ranked = run {
            val beforeComposing = if (content.composing.isValid) {
                content.textBeforeSelection.dropLast(composing.length)
            } else {
                content.textBeforeSelection
            }
            val previousWords = PersonalLearning.extractLastWords(beforeComposing, 2)
            val previousWord = previousWords.lastOrNull() ?: return@run merged
            val prevNorm = normalizer.normalize(previousWord)
            val nowMs = System.currentTimeMillis()
            // norm -> (الشكل المعروض, القوة): الشكل يبقى حيًّا كي يُبذَر المرشح السياقي في الشريط
            // نفسه — لا أن يُستشار للترجيح فقط ثم يغرق ما تنبأنا به تحت إكمالات التكرار العام.
            val followerForms = HashMap<String, Pair<String, Int>>(32)
            fun addFollower(display: String, rawFreq: Int) {
                val freq = rawFreq.coerceAtMost(255)
                val norm = normalizer.normalize(display)
                val current = followerForms[norm]
                if (current == null || freq > current.second) followerForms[norm] = display to freq
            }
            dictionary?.nextWords(prevNorm, CONTEXT_FOLLOWER_FETCH)?.forEach { (word, freq) ->
                addFollower(word, freq)
            }
            learning.bigramsFor(language, prevNorm, CONTEXT_FOLLOWER_FETCH).forEach { (word, count, lastMs) ->
                addFollower(
                    word,
                    (PersonalLearning.userBigramFreq(count) * PersonalLearning.recencyWeight(nowMs, lastMs)).toInt(),
                )
            }
            if (previousWords.size == 2) {
                val w12Norm = "${normalizer.normalize(previousWords[0])} $prevNorm"
                dictionary?.nextWords2(w12Norm, CONTEXT_FOLLOWER_FETCH)?.forEach { (word, freq) ->
                    addFollower(word, (freq * PersonalLearning.TRIGRAM_FOLLOWER_BOOST).toInt())
                }
                learning.trigramsFor(language, w12Norm, CONTEXT_FOLLOWER_FETCH).forEach { (word, count, lastMs) ->
                    addFollower(
                        word,
                        (PersonalLearning.userBigramFreq(count) * PersonalLearning.recencyWeight(nowMs, lastMs) *
                            PersonalLearning.TRIGRAM_FOLLOWER_BOOST).toInt(),
                    )
                }
            }
            val followerFreqs = HashMap<String, Int>(followerForms.size)
            for ((norm, form) in followerForms) followerFreqs[norm] = form.second
            val reranked = PersonalLearning.rerankByContext(merged, followerFreqs, normalizer)
            // بذر مرشّحي السياق: «إن» ثم «ش» تُبقي «شاء» و«شالله» في صدر الشريط.
            PersonalLearning.seedContextCompletions(reranked, followerForms, query, maxCandidateCount)
        }

        val hasExactMatch = ranked.first().isExactMatch
        val autoCorrectEnabled = prefs.correction.autoCorrectEnabled.get()
        val top = ranked.first()
        val second = ranked.getOrNull(1)
        val autoCommitTop = autoCorrectEnabled &&
            !hasExactMatch &&
            top.isCorrection &&
            // A fuzzy-prefix match's distance covers only the typed prefix — auto-committing
            // would append word tails the user never typed, so it stays tap-only.
            !top.isPrefixMatch &&
            composing.length >= AUTO_CORRECT_MIN_COMPOSING_LENGTH &&
            top.distance <= AUTO_CORRECT_MAX_DISTANCE &&
            top.entry.freq >= AUTO_CORRECT_MIN_FREQ &&
            (second == null || top.score >= second.score * AUTO_CORRECT_MIN_SCORE_MARGIN) &&
            !learning.isAutocorrectBlocked(top.entry.word)

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

    /**
     * Next-word prediction: static bigram/trigram tables blended with the user's personal
     * bigrams/trigrams/quadgrams/pentagrams (context up to FOUR preceding words), recency-weighted
     * and backoff-blended — the longer and fresher the matched context, the stronger its say.
     * On top of the single-word candidates, a confident chain of continuations (up to
     * [PersonalLearning.CHAIN_MAX_WORDS] words) is offered as one tappable phrase; and when the
     * context is a sentence start or runs dry, the user's own lexicon fills the bar instead of
     * leaving it dead.
     */
    private suspend fun suggestNextWords(
        language: String,
        dictionary: SqliteWordDictionary?,
        normalizer: WordNormalizer,
        content: EditorContent,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        val previousWords = PersonalLearning.extractLastWords(content.textBeforeSelection, 4)
        val userIdx = userIndexFor(language)
        val nowMs = System.currentTimeMillis()
        val blocked = learning.blockedWords(language)

        /** Junk filter: only predict words that are known — statically or learned. A word the
         *  user typed at least twice in this exact context counts as evidence on its own, so a
         *  formula-final word like «وبركاته» keeps chaining even before it graduates into the
         *  user dictionary. */
        fun isKnownWord(word: String): Boolean {
            val norm = normalizer.normalize(word)
            return dictionary?.index?.exact(norm)?.isNotEmpty() == true || userIdx.exact(norm).isNotEmpty()
        }
        fun isTrustedPrediction(word: String, count: Int): Boolean {
            return count >= PersonalLearning.PENDING_THRESHOLD || isKnownWord(word)
        }
        /** Personal followers -> (word, freq 0..255): trust-filtered, count-scaled, recency-weighted. */
        fun personalFreqs(followers: List<LearningStore.Follower>): List<Pair<String, Int>> {
            return followers.asSequence()
                .filter { isTrustedPrediction(it.word, it.count) }
                .map {
                    it.word to (PersonalLearning.userBigramFreq(it.count) *
                        PersonalLearning.recencyWeight(nowMs, it.lastMs)).toInt().coerceAtMost(255)
                }
                .toList()
        }

        /** All context orders for [contextWords], blended best-first — the one ranking engine
         *  used both for the suggestion bar and for every step of the phrase chain. */
        fun followersFor(contextWords: List<String>, fetch: Int): List<Pair<String, Int>> {
            val prevNorm = normalizer.normalize(contextWords.last())
            val orders = buildList {
                add(dictionary?.nextWords(prevNorm, fetch) ?: emptyList())
                add(personalFreqs(learning.bigramsFor(language, prevNorm, fetch)))
                if (contextWords.size >= 2) {
                    val w12Norm = contextWords.takeLast(2).joinToString(" ") { normalizer.normalize(it) }
                    add(PersonalLearning.boostTrigramPredictions(dictionary?.nextWords2(w12Norm, fetch) ?: emptyList()))
                    add(PersonalLearning.boostTrigramPredictions(personalFreqs(learning.trigramsFor(language, w12Norm, fetch))))
                }
                if (contextWords.size >= 3) {
                    val w123Norm = contextWords.takeLast(3).joinToString(" ") { normalizer.normalize(it) }
                    add(PersonalLearning.boostQuadgramPredictions(personalFreqs(learning.quadgramsFor(language, w123Norm, fetch))))
                }
                if (contextWords.size >= 4) {
                    val w1234Norm = contextWords.takeLast(4).joinToString(" ") { normalizer.normalize(it) }
                    add(PersonalLearning.boostPentagramPredictions(personalFreqs(learning.pentagramsFor(language, w1234Norm, fetch))))
                }
            }
            return PersonalLearning.mergeNextWordsBlended(orders, blocked, fetch)
        }

        /** The user's own lexicon as a modest fallback — better a familiar word than a dead bar. */
        fun lexiconFallback(): List<Pair<String, Int>> {
            val exclude = previousWords.lastOrNull()?.let { normalizer.normalize(it) }
            return learning.topWordsFor(language, maxCandidateCount * 2).asSequence()
                .filter { (word, count) ->
                    PersonalLearning.isLearnableWord(word) && word !in blocked &&
                        isTrustedPrediction(word, count) && normalizer.normalize(word) != exclude
                }
                .take(maxCandidateCount)
                .map { (word, count) -> word to (40 + 8 * count).coerceAtMost(160) }
                .toList()
        }

        val merged: List<Pair<String, Int>>
        if (previousWords.isEmpty()) {
            // بداية جملة: عاداتُ الافتتاح المتعلمة أولًا، ثم معجم المستخدم إن لم توجد.
            val starters = personalFreqs(
                learning.bigramsFor(language, PersonalLearning.SENTENCE_START_KEY, maxCandidateCount),
            ).filter { (word, _) -> word !in blocked }
            merged = starters.ifEmpty { lexiconFallback() }.take(maxCandidateCount)
        } else {
            merged = followersFor(previousWords, maxCandidateCount).ifEmpty { lexiconFallback() }
        }
        if (merged.isEmpty()) return emptyList()

        val candidates = merged.map { (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                secondaryText = null,
                confidence = (freq / 255.0).coerceIn(0.0, 1.0),
                isEligibleForAutoCommit = false,
                sourceProvider = this,
            ) as SuggestionCandidate
        }.toMutableList()

        // سلسلة العبارة: امتدادٌ واثق حتى خمس كلمات يُقبل بلمسة واحدة — يتقدم فقط ما دام
        // النموذج قاطعًا (قوة الحلقة وهيمنتها في PersonalLearning.chainStep)، ومفترقُ الطرق يوقفه.
        if (previousWords.isNotEmpty()) {
            val chain = mutableListOf<String>()
            var context = previousWords
            var followers = merged
            var weakestLink = 255
            while (chain.size < PersonalLearning.CHAIN_MAX_WORDS) {
                val next = PersonalLearning.chainStep(followers) ?: break
                weakestLink = minOf(weakestLink, followers.first().second)
                chain += next
                if (chain.size == PersonalLearning.CHAIN_MAX_WORDS) break
                context = (context + next).takeLast(4)
                followers = followersFor(context, CONTEXT_FOLLOWER_FETCH)
            }
            if (chain.size >= 2) {
                val phrase = WordSuggestionCandidate(
                    text = chain.joinToString(" "),
                    secondaryText = null,
                    confidence = (weakestLink / 255.0).coerceIn(0.0, 1.0),
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                )
                candidates.add(1.coerceAtMost(candidates.size), phrase)
                if (candidates.size > maxCandidateCount + 1) {
                    candidates.removeAt(candidates.lastIndex)
                }
            }
        }
        return candidates
    }

    // --- spelling --------------------------------------------------------------------------------

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val language = subtype.primaryLocale.language
        val dictionary = dictionaryFor(subtype) ?: return SpellingResult.unspecified()
        val normalizer = WordNormalizer.forLanguage(language)
        val norm = normalizer.normalize(word)
        if (norm.isEmpty() ||
            dictionary.index.exact(norm).isNotEmpty() ||
            userIndexFor(language).exact(norm).isNotEmpty()
        ) {
            return SpellingResult.validWord()
        }
        val staticCorrections = dictionary.index.suggest(norm, maxSuggestionCount, allowPossiblyOffensive)
        val userCorrections = userIndexFor(language).suggest(norm, maxSuggestionCount, allowPossiblyOffensive)
        val corrections = (userCorrections + staticCorrections)
            .filter { it.isCorrection || it.isExactMatch }
            .sortedByDescending { it.score }
            .map { it.entry.word }
            .distinct()
            .take(maxSuggestionCount)
        return if (corrections.isEmpty()) {
            SpellingResult.validWord() // Unknown word, no plausible correction: don't mark as typo.
        } else {
            SpellingResult.typo(corrections.toTypedArray())
        }
    }

    // --- learning --------------------------------------------------------------------------------

    override suspend fun notifyWordCommitted(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        isPrivateSession: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        if (isPrivateSession) return@withContext
        val language = subtype.primaryLocale.language
        val cleanWord = word.trim()
        if (!PersonalLearning.isLearnableWord(cleanWord)) return@withContext
        // Local typing statistics: independent of the learn-from-typing switch (it's counting,
        // not learning), but still never recorded in private sessions.
        learning.recordTypedWord(language, cleanWord)
        if (!prefs.suggestion.learnFromTyping.get()) return@withContext
        if (learning.isWordBlocked(language, cleanWord)) return@withContext
        val normalizer = WordNormalizer.forLanguage(language)
        val dictionary = dictionaryFor(subtype)

        // Unigram learning: bump user words, acquire unknown words after repeated sightings.
        try {
            dictionaryManager.loadUserDictionariesIfNecessary()
            val dao = dictionaryManager.florisUserDictionaryDao()
            val userEntry = dao?.queryExact(cleanWord)?.firstOrNull { matchesLanguage(it.locale, language) }
            when {
                userEntry != null -> {
                    dao.update(userEntry.copy(freq = PersonalLearning.bumpedFreq(userEntry.freq)))
                    invalidateUserIndex()
                }
                dictionary?.index?.exact(normalizer.normalize(cleanWord))?.isNotEmpty() == true -> {
                    // Known static word: nothing to acquire (its frequency is corpus-driven).
                }
                else -> {
                    val count = learning.recordPendingWord(language, cleanWord)
                    if (count >= PersonalLearning.PENDING_THRESHOLD && dao != null) {
                        dao.insert(UserDictionaryEntry(0, cleanWord, PersonalLearning.NEW_WORD_FREQ, language, null))
                        learning.clearPendingWord(language, cleanWord)
                        invalidateUserIndex()
                        flogDebug { "learned new word: $cleanWord ($language)" }
                    }
                }
            }
        } catch (e: Exception) {
            flogError { "unigram learning failed: $e" }
        }

        // Personal bigram → pentagram learning: a 5+ word formula typed in sequence deposits an
        // n-gram at every depth, so it can chain back word by word — five words deep — later.
        val prev = precedingWords.lastOrNull()?.trim().orEmpty()
        if (PersonalLearning.isLearnableWord(prev)) {
            learning.recordBigram(language, normalizer.normalize(prev), cleanWord)
            val prev2 = precedingWords.getOrNull(precedingWords.size - 2)?.trim().orEmpty()
            if (PersonalLearning.isLearnableWord(prev2)) {
                learning.recordTrigram(
                    language,
                    "${normalizer.normalize(prev2)} ${normalizer.normalize(prev)}",
                    cleanWord,
                )
                val prev3 = precedingWords.getOrNull(precedingWords.size - 3)?.trim().orEmpty()
                if (PersonalLearning.isLearnableWord(prev3)) {
                    learning.recordQuadgram(
                        language,
                        "${normalizer.normalize(prev3)} ${normalizer.normalize(prev2)} ${normalizer.normalize(prev)}",
                        cleanWord,
                    )
                    val prev4 = precedingWords.getOrNull(precedingWords.size - 4)?.trim().orEmpty()
                    if (PersonalLearning.isLearnableWord(prev4)) {
                        learning.recordPentagram(
                            language,
                            listOf(prev4, prev3, prev2, prev).joinToString(" ") { normalizer.normalize(it) },
                            cleanWord,
                        )
                    }
                }
            }
        } else if (precedingWords.isEmpty()) {
            // بداية جملة (لا سياق قبلها): تُحفظ كعادة افتتاحٍ تحت المفتاح الاصطناعي —
            // فتعود أول اقتراحات الشريط عند كل بداية سطر.
            learning.recordBigram(language, PersonalLearning.SENTENCE_START_KEY, cleanWord)
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Unigram/bigram learning is handled uniformly by notifyWordCommitted, which fires for
        // candidate commits too (see KeyboardManager.commitCandidate).
        flogDebug { "accepted: ${candidate.text}" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // The user undid an auto-correction with backspace: negative signal. After enough reverts
        // the engine stops auto-committing to this word (it remains a tappable suggestion).
        withContext(Dispatchers.IO) {
            learning.recordAutocorrectRevert(candidate.text.toString())
        }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return withContext(Dispatchers.IO) {
            val language = subtype.primaryLocale.language
            val word = candidate.text.toString()
            learning.blockWord(language, word)
            try {
                dictionaryManager.florisUserDictionaryDao()?.let { dao ->
                    dao.queryExact(word).filter { matchesLanguage(it.locale, language) }.forEach { dao.delete(it) }
                }
            } catch (e: Exception) {
                flogError { "failed to delete user dictionary entry: $e" }
            }
            invalidateUserIndex()
            true
        }
    }

    // --- glide interop ---------------------------------------------------------------------------

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
        learning.close()
    }
}
