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
    const val USER_SCORE_BOOST = 1.3

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
     * user scores are boosted by [USER_SCORE_BOOST], duplicates keep their best variant
     * (an exact-match flavor always beats a non-exact one), blocked words are dropped,
     * exact matches stay pinned before everything else.
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
            val boosted = ranked.copy(score = ranked.score * USER_SCORE_BOOST)
            merged.merge(boosted.entry.word, boosted) { a, b ->
                when {
                    a.isExactMatch != b.isExactMatch -> if (a.isExactMatch) a else b
                    a.score >= b.score -> a
                    else -> b
                }
            }
        }
        return merged.values.asSequence()
            .filter { it.entry.word !in blocked }
            .sortedWith(compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score })
            .take(maxCount)
            .toList()
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
