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

/**
 * The pure, unit-tested rules of the personal-learning feature. Everything Android-flavored
 * (Room user dictionary, the [LearningStore] SQLite database, provider wiring) lives elsewhere;
 * this object only decides numbers and ordering.
 */
object PersonalLearning {

    /** An unknown word must be typed this many times before it enters the user dictionary. */
    const val PENDING_THRESHOLD = 2

    /** Frequency a freshly learned word starts with (below the 128 default of manual entries). */
    const val NEW_WORD_FREQ = 112

    /** How much an accepted/re-typed user word is boosted per use. */
    const val FREQ_BUMP = 8

    /** Ranking boost applied to user-dictionary suggestions over equal-frequency static ones. */
    const val USER_SCORE_BOOST = 2.0

    /**
     * Score floor added to user exact matches and clean completions (distance 0.0): 256.0 sits
     * just above the highest possible static non-exact score (freq cap 255), so a word the user
     * actually types outranks every static completion and correction — while staying far below
     * [WordIndex.EXACT_MATCH_BASE], so a correctly typed word is never overridden by a learned
     * one. Deliberately NOT applied to fuzzy corrections: a floored correction would sit on a
     * 256+ scale that the relative autocommit margin gate compares against 0..255 static scores,
     * letting a learned word silently auto-replace a correctly typed unknown word.
     */
    const val USER_PRIORITY_FLOOR = 256.0

    /** Auto-correcting to a word stops after the user reverted it this many times. */
    const val AUTOCORRECT_BLOCK_THRESHOLD = 2

    /** Word shape eligible for learning: 2..24 letters (apostrophe allowed for Latin elisions). */
    fun isLearnableWord(word: String): Boolean {
        return word.length in 2..24 && word.all { it.isLetter() || it == '\'' }
    }

    fun bumpedFreq(oldFreq: Int): Int = (oldFreq + FREQ_BUMP).coerceAtMost(255)

    /** Maps a raw personal-bigram count onto the 0..255 scale used by static bigrams. */
    fun userBigramFreq(count: Int): Int = (60 + 24 * count).coerceAtMost(255)

    /**
     * Merges static-dictionary and user-dictionary rankings for one query:
     * user scores are boosted by [USER_SCORE_BOOST], and close user matches (exact/completion/
     * one-edit correction) are additionally lifted above [USER_PRIORITY_FLOOR] so the user's own
     * vocabulary wins over static suggestions. Duplicates keep their best variant (an exact-match
     * flavor always beats a non-exact one), blocked words are dropped, exact matches stay pinned
     * before everything else.
     */
    fun mergeRanked(
        static: List<RankedWord>,
        user: List<RankedWord>,
        blocked: Set<String>,
        maxCount: Int,
    ): List<RankedWord> {
        val merged = LinkedHashMap<String, RankedWord>(static.size + user.size)
        for (ranked in static) {
            merged[ranked.entry.word] = ranked
        }
        for (ranked in user) {
            val base = ranked.score * USER_SCORE_BOOST
            val score = if (ranked.isExactMatch || ranked.distance == 0.0) {
                maxOf(base, USER_PRIORITY_FLOOR + ranked.score)
            } else base
            val boosted = ranked.copy(score = score)
            merged.merge(boosted.entry.word, boosted) { a, b ->
                when {
                    a.isExactMatch != b.isExactMatch -> if (a.isExactMatch) a else b
                    a.score >= b.score -> a
                    else -> b
                }
            }
        }
        val sorted = merged.values.asSequence()
            .filter { it.entry.word !in blocked }
            .sortedWith(compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score })
            .toList()
        val top = sorted.take(maxCount).toMutableList()
        // The query isn't a known word and completions of the (mistyped) prefix filled every
        // slot: reserve one slot for the best correction so the intended word stays reachable.
        // (WordIndex has the same reservation internally, but it is called with 2x maxCount
        // here, so the re-sort above would otherwise drop the reserved candidate again.)
        if (top.size >= maxCount && top.none { it.isExactMatch } && top.none { it.isCorrection }) {
            val bestCorrection = sorted.firstOrNull { it.isCorrection }
            if (bestCorrection != null) {
                top.removeAt(top.lastIndex)
                top.add(1.coerceAtMost(top.size), bestCorrection)
            }
        }
        return top
    }

    /** Weight of the previous-word (bigram) signal when reranking corrections/completions. */
    const val CONTEXT_BOOST_WEIGHT = 0.6

    /**
     * How much stronger a two-word-context (trigram) signal is than a one-word (bigram) one.
     * A trigram follower's freq is scaled by this before being max-merged with the bigram
     * followers, so «إن شاء» → «الله» beats whatever merely follows «شاء» alone.
     */
    const val TRIGRAM_FOLLOWER_BOOST = 1.35

    /** Freq advantage of trigram-based next-word predictions over bigram-based ones. */
    const val TRIGRAM_PREDICTION_BOOST = 1.25

    /** Freq advantage of quadgram-based (3-word context) predictions — the longer the matched
     *  context, the more specific and trustworthy the continuation, so it outranks trigrams. */
    const val QUADGRAM_PREDICTION_BOOST = 1.45

    /** Freq advantage of pentagram-based (4-word context) predictions — the deepest context the
     *  engine keeps; what lets the fifth word of a personal formula arrive with full memory. */
    const val PENTAGRAM_PREDICTION_BOOST = 1.6

    /** Scales quadgram next-word predictions by [QUADGRAM_PREDICTION_BOOST] (capped at 255). */
    fun boostQuadgramPredictions(predictions: List<Pair<String, Int>>): List<Pair<String, Int>> {
        return predictions.map { (word, freq) ->
            word to (freq * QUADGRAM_PREDICTION_BOOST).toInt().coerceAtMost(255)
        }
    }

    /** Scales pentagram next-word predictions by [PENTAGRAM_PREDICTION_BOOST] (capped at 255). */
    fun boostPentagramPredictions(predictions: List<Pair<String, Int>>): List<Pair<String, Int>> {
        return predictions.map { (word, freq) ->
            word to (freq * PENTAGRAM_PREDICTION_BOOST).toInt().coerceAtMost(255)
        }
    }

    /**
     * Pseudo-word key under which sentence-start habits are stored as personal bigrams:
     * the words the user opens sentences with («السلام», «طيب», «صباح»…) become followers of
     * this key, so an empty context is a real prediction context instead of a dead bar.
     * Normalized words can never contain '<', so the key cannot collide with real text.
     */
    const val SENTENCE_START_KEY = "<s>"

    /**
     * Recency weight for personal n-grams: what the user said this week matters more than what
     * they said last month. `lastMs == 0` (rows recorded before the column existed) stays
     * neutral — old habits are not punished for missing timestamps.
     */
    fun recencyWeight(nowMs: Long, lastMs: Long): Double {
        if (lastMs <= 0L || lastMs > nowMs) return 1.0
        val age = nowMs - lastMs
        return when {
            age <= 2L * 24 * 60 * 60 * 1000 -> 1.25
            age <= 7L * 24 * 60 * 60 * 1000 -> 1.15
            age <= 30L * 24 * 60 * 60 * 1000 -> 1.0
            else -> 0.85
        }
    }

    /**
     * Cross-order evidence bonus used by [mergeNextWordsBlended]: a follower that several
     * context depths agree on (e.g. both the bigram and the trigram tables predict it) is more
     * trustworthy than one a single order shouts about — each extra supporting order adds this
     * fraction of its freq on top of the strongest signal.
     */
    const val CROSS_ORDER_BONUS = 0.12

    /**
     * Backoff-style blend of next-word predictions from several context orders (each list is
     * (word, freq 0..255), already order-boosted). Replaces naive max-merge: the base is still
     * the strongest single signal, but agreement across orders adds [CROSS_ORDER_BONUS] of the
     * supporting freqs, so «word all three tables expect» beats «word one table shouts».
     */
    fun mergeNextWordsBlended(
        orders: List<List<Pair<String, Int>>>,
        blocked: Set<String>,
        maxCount: Int,
    ): List<Pair<String, Int>> {
        val best = LinkedHashMap<String, Int>()
        val support = HashMap<String, Int>()
        for (order in orders) {
            for ((word, freq) in order) {
                best.merge(word, freq, ::maxOf)
                support.merge(word, freq, Int::plus)
            }
        }
        return best.entries.asSequence()
            .filter { it.key !in blocked }
            .map { (word, freq) ->
                val extra = ((support[word] ?: freq) - freq) * CROSS_ORDER_BONUS
                word to (freq + extra).toInt().coerceAtMost(255)
            }
            .sortedByDescending { it.second }
            .take(maxCount)
            .toList()
    }

    /** Longest continuation offered as a single phrase candidate — the five-word chain. */
    const val CHAIN_MAX_WORDS = 5

    /** A chain link must be at least this strong (0..255) to keep extending the phrase. */
    const val CHAIN_MIN_LINK_FREQ = 96

    /** The top follower must beat the runner-up by this factor for the chain to trust it. */
    const val CHAIN_DOMINANCE = 1.3

    /**
     * Picks the next word of a phrase chain from ranked [followers], or null when the model
     * isn't sure enough: the top follower must be strong ([CHAIN_MIN_LINK_FREQ]) and clearly
     * dominant over the runner-up ([CHAIN_DOMINANCE]) — a fork in the road ends the phrase,
     * because guessing wrong five words deep costs the user more than it saves.
     */
    fun chainStep(followers: List<Pair<String, Int>>): String? {
        val top = followers.firstOrNull() ?: return null
        if (top.second < CHAIN_MIN_LINK_FREQ) return null
        val second = followers.getOrNull(1)
        if (second != null && top.second < second.second * CHAIN_DOMINANCE) return null
        return top.first
    }

    /** Max-merges bigram followers with trigram followers, the latter scaled by
     *  [TRIGRAM_FOLLOWER_BOOST] (capped at 255). Both maps are norm -> freq. */
    fun blendFollowers(bigrams: Map<String, Int>, trigrams: Map<String, Int>): Map<String, Int> {
        if (trigrams.isEmpty()) return bigrams
        val blended = HashMap<String, Int>(bigrams.size + trigrams.size)
        blended.putAll(bigrams)
        for ((norm, freq) in trigrams) {
            val boosted = (freq * TRIGRAM_FOLLOWER_BOOST).toInt().coerceAtMost(255)
            blended.merge(norm, boosted, ::maxOf)
        }
        return blended
    }

    /** Scales trigram next-word predictions by [TRIGRAM_PREDICTION_BOOST] (capped at 255). */
    fun boostTrigramPredictions(predictions: List<Pair<String, Int>>): List<Pair<String, Int>> {
        return predictions.map { (word, freq) ->
            word to (freq * TRIGRAM_PREDICTION_BOOST).toInt().coerceAtMost(255)
        }
    }

    /**
     * Extracts up to [maxWords] trailing words before the cursor, oldest first. Punctuation
     * (a sentence/clause boundary) closes the context, so a trigram context never crosses
     * «مرحبا، كيف» — only the words after the comma count.
     *
     * Combining marks (category Mn — Arabic harakat/shadda/tanween) are part of the word they
     * follow, and format characters (category Cf — RLM/ALM/ZWJ that apps insert between words)
     * are transparent separators: neither may break the context, otherwise diacritized text like
     * «إن شاءَ» would lose its n-gram context entirely and feed wrong fragments into learning.
     * The normalizer strips the marks later, so «شاءَ» still yields the norm key «شاء».
     */
    fun extractLastWords(text: CharSequence, maxWords: Int): List<String> {
        fun isWordChar(ch: Char): Boolean =
            ch.isLetter() || ch == '\'' || ch.category == CharCategory.NON_SPACING_MARK
        fun isTransparentSeparator(ch: Char): Boolean =
            ch.isWhitespace() || ch.category == CharCategory.FORMAT
        val words = ArrayDeque<String>()
        var end = text.length
        outer@ while (words.size < maxWords) {
            while (end > 0 && !isWordChar(text[end - 1])) {
                if (!isTransparentSeparator(text[end - 1])) break@outer
                end--
            }
            if (end == 0) break
            var start = end
            while (start > 0 && isWordChar(text[start - 1])) {
                start--
            }
            words.addFirst(text.substring(start, end))
            end = start
        }
        return words.toList()
    }

    /**
     * Context-aware reranking: candidates whose normalized form is a known follower of the
     * previous word get their score boosted proportionally to the bigram frequency, so typing
     * "صباح الخ" ranks "الخير" above an otherwise more frequent stray match, and an ambiguous
     * correction resolves toward what actually follows the preceding word.
     *
     * Exact matches keep their pinned-first position (their score is orders of magnitude above
     * the boost anyway); everything else re-sorts by the boosted score.
     *
     * @param followers normalized follower word -> bigram freq (0..255) for the preceding word.
     */
    fun rerankByContext(
        ranked: List<RankedWord>,
        followers: Map<String, Int>,
        normalizer: WordNormalizer,
    ): List<RankedWord> {
        if (followers.isEmpty() || ranked.size < 2) return ranked
        var changed = false
        val boosted = ranked.map { candidate ->
            val bigramFreq = followers[normalizer.normalize(candidate.entry.word)] ?: return@map candidate
            changed = true
            candidate.copy(score = candidate.score * (1.0 + CONTEXT_BOOST_WEIGHT * (bigramFreq / 255.0)))
        }
        if (!changed) return ranked
        return boosted.sortedWith(
            compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score }
        )
    }

    /** Score band for context-endorsed completions: far above every frequency-ranked completion
     *  (static cap 255, user floor 256+) yet far below [WordIndex.EXACT_MATCH_BASE] — the typed
     *  word always wins, and the context always beats raw global frequency. */
    const val CONTEXT_COMPLETION_FLOOR = 500.0

    /** How much of the follower freq (0..255) is added on top of [CONTEXT_COMPLETION_FLOOR]. */
    const val CONTEXT_COMPLETION_WEIGHT = 2.0

    /** At most this many context-endorsed completions are seeded — the bar stays diverse. */
    const val CONTEXT_COMPLETION_MAX = 3

    /**
     * Seeds context-endorsed completions into the composing ranking — the fix for «إن ← ش»:
     * the engine predicted «شاء» after «إن», so the moment the user types «ش» that prediction
     * must LEAD the bar, not drown under globally frequent ش-words. Followers of the preceding
     * words whose norm starts with the typed [query] are lifted into a score band above every
     * frequency-ranked completion (existing candidates lifted in place, absent ones injected);
     * exact matches stay pinned, and the correction-slot reservation is re-applied afterwards
     * so the seeding never evicts the only plausible correction.
     *
     * @param followerForms norm -> (display form, follower freq 0..255) for the preceding context.
     */
    fun seedContextCompletions(
        ranked: List<RankedWord>,
        followerForms: Map<String, Pair<String, Int>>,
        query: String,
        maxCount: Int,
    ): List<RankedWord> {
        if (followerForms.isEmpty() || query.isEmpty()) return ranked
        val endorsed = followerForms.asSequence()
            .filter { (norm, _) -> norm != query && norm.startsWith(query) }
            .sortedByDescending { it.value.second }
            .take(CONTEXT_COMPLETION_MAX)
            .toList()
        if (endorsed.isEmpty()) return ranked
        val byWord = LinkedHashMap<String, RankedWord>(ranked.size + endorsed.size)
        for (candidate in ranked) {
            byWord[candidate.entry.word] = candidate
        }
        for ((norm, form) in endorsed) {
            val (display, freq) = form
            val score = CONTEXT_COMPLETION_FLOOR + CONTEXT_COMPLETION_WEIGHT * freq
            val existing = byWord[display]
            if (existing != null) {
                if (!existing.isExactMatch && existing.score < score) {
                    byWord[display] = existing.copy(score = score)
                }
            } else {
                byWord[display] = RankedWord(
                    entry = WordEntry(display, norm, freq),
                    score = score,
                    distance = 0.0,
                    isExactMatch = false,
                    isCorrection = false,
                )
            }
        }
        val sorted = byWord.values.sortedWith(
            compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score }
        )
        val top = sorted.take(maxCount).toMutableList()
        if (top.size >= maxCount && top.none { it.isExactMatch } && top.none { it.isCorrection }) {
            val bestCorrection = sorted.firstOrNull { it.isCorrection }
            if (bestCorrection != null) {
                top.removeAt(top.lastIndex)
                top.add(1.coerceAtMost(top.size), bestCorrection)
            }
        }
        return top
    }

    /**
     * Merges static and personal next-word predictions ((word, freq 0..255) pairs, each list
     * already sorted best-first), deduplicating on word with max freq and dropping blocked words.
     */
    fun mergeNextWords(
        static: List<Pair<String, Int>>,
        personal: List<Pair<String, Int>>,
        blocked: Set<String>,
        maxCount: Int,
    ): List<Pair<String, Int>> {
        val merged = LinkedHashMap<String, Int>(static.size + personal.size)
        for ((word, freq) in static + personal) {
            merged.merge(word, freq, ::maxOf)
        }
        return merged.entries.asSequence()
            .filter { it.key !in blocked }
            .sortedByDescending { it.value }
            .take(maxCount)
            .map { it.key to it.value }
            .toList()
    }
}
