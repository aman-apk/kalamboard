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

/** A single dictionary entry. [flags] bit 0 marks a possibly-offensive word. */
data class WordEntry(
    val word: String,
    val norm: String,
    val freq: Int,
    val flags: Int = 0,
) {
    val isPossiblyOffensive: Boolean get() = (flags and FLAG_POSSIBLY_OFFENSIVE) != 0

    companion object {
        const val FLAG_POSSIBLY_OFFENSIVE = 1
    }
}

/** One ranked suggestion produced by [WordIndex]. */
data class RankedWord(
    val entry: WordEntry,
    val score: Double,
    /** Weighted edit distance for corrections; 0.0 for exact matches and pure completions. */
    val distance: Double,
    val isExactMatch: Boolean,
    val isCorrection: Boolean,
    /** True when [distance] covers only a *prefix* of the entry (fuzzy-prefix completion):
     *  the unmatched tail was never typed, so such a candidate must never auto-commit. */
    val isPrefixMatch: Boolean = false,
)

/**
 * Pure in-memory suggestion index over one language's lexicon: frequency-ranked prefix completion
 * plus proximity-weighted fuzzy correction. Deliberately free of any Android dependency so the
 * whole ranking behavior is unit-testable on the JVM.
 *
 * The entry array is sorted by [WordEntry.norm]; prefix completion is two binary searches plus a
 * bounded scan, fuzzy correction is a length-banded weighted Damerau-Levenshtein sweep with early
 * abandon. Both are fast enough (<10ms for a 100k lexicon) to run per keystroke on a background
 * dispatcher.
 */
class WordIndex(entries: List<WordEntry>, private val proximity: KeyProximity) {

    private val sorted: Array<WordEntry> = entries.sortedWith(
        compareBy<WordEntry> { it.norm }.thenByDescending { it.freq }
    ).toTypedArray()

    /** Exact-norm lookup table; a norm key may map to several display spellings (أ/ا variants). */
    private val byNorm: Map<String, List<WordEntry>> = sorted.groupBy { it.norm }

    val size: Int get() = sorted.size

    fun words(): List<String> = sorted.map { it.word }

    fun exact(norm: String): List<WordEntry> = byNorm[norm] ?: emptyList()

    fun frequencyOf(word: String, normalizer: WordNormalizer): Int {
        val norm = normalizer.normalize(word)
        return exact(norm).firstOrNull { it.word == word }?.freq
            ?: exact(norm).firstOrNull()?.freq
            ?: 0
    }

    /**
     * Ranks suggestions for the normalized composing text [query]:
     * 1. exact matches (every display spelling of the query's norm) pinned on top,
     * 2. prefix completions ranked by frequency scaled with completion closeness,
     * 3. fuzzy corrections ranked by frequency scaled with (proximity-weighted) edit distance.
     */
    fun suggest(query: String, maxCount: Int, allowPossiblyOffensive: Boolean): List<RankedWord> {
        if (query.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, RankedWord>(maxCount * 4)

        // 1. Exact matches.
        for (entry in exact(query)) {
            results[entry.word] = RankedWord(
                entry = entry,
                score = EXACT_MATCH_BASE + entry.freq,
                distance = 0.0,
                isExactMatch = true,
                isCorrection = false,
            )
        }

        // 2. Prefix completions.
        var lo = lowerBound(query)
        val hi = upperBoundOfPrefix(query)
        while (lo < hi) {
            val entry = sorted[lo]
            lo++
            if (entry.norm.length == query.length) continue // exact matches already added
            val closeness = query.length.toDouble() / entry.norm.length
            val score = entry.freq * (0.55 + 0.45 * closeness)
            merge(results, entry, score, distance = 0.0, isExact = false, isCorrection = false)
        }

        // 3. Fuzzy corrections and fuzzy-prefix completions.
        if (query.length >= MIN_FUZZY_QUERY_LENGTH) {
            val maxCost = if (query.length >= 5) 2.0 else 1.0
            val minLen = query.length - maxCost.toInt()
            val maxLen = query.length + maxCost.toInt()
            // Lead-window prune: with at most `maxCost` edits, the alignment of the leading
            // characters can shift by at most maxCost positions, so query and entry must share
            // at least one character within their first (maxCost + 1) positions. This is sound
            // for the whole budget (unlike a fixed two-char check, which would wrongly drop
            // double adjacent-key slips like «jwllo» → «hello» on the 2.0 budget).
            val leadWindow = maxCost.toInt() + 1
            val queryLead = minOf(leadWindow, query.length)
            for (entry in sorted) {
                val norm = entry.norm
                val len = norm.length
                if (len < minLen) continue
                if (norm.startsWith(query)) continue // already covered as completion
                var sharesLead = false
                val entryLead = minOf(leadWindow, len)
                outer@ for (qi in 0 until queryLead) {
                    val qc = query[qi]
                    for (ei in 0 until entryLead) {
                        if (norm[ei] == qc) {
                            sharesLead = true
                            break@outer
                        }
                    }
                }
                if (!sharesLead) continue
                if (len <= maxLen) {
                    // Whole-word correction: the query is a complete mistyped word.
                    val dist = weightedEditDistance(query, norm, maxCost)
                    if (dist <= maxCost) {
                        val score = entry.freq * (1.0 - dist / (maxCost + CORRECTION_PENALTY_SLACK))
                        merge(results, entry, score, dist, isExact = false, isCorrection = true)
                    }
                }
                if (len > query.length && query.length >= MIN_FUZZY_PREFIX_QUERY_LENGTH) {
                    // Fuzzy-prefix completion: the query is a mistyped PREFIX of a longer word
                    // — the case a plain length band can never reach mid-word. Also tried for
                    // entries inside the band (merge keeps the better score), so the completion
                    // tail is never billed as edit errors. Gated on 3+ typed characters: on a
                    // 2-char query the prefix alignment could stop after one matched character,
                    // flooding the bar with every word sharing just the first letter.
                    val dist = weightedPrefixDistance(query, norm, maxCost)
                    if (dist <= maxCost) {
                        val closeness = query.length.toDouble() / len
                        val score = entry.freq * (0.55 + 0.45 * closeness) *
                            (1.0 - dist / (maxCost + CORRECTION_PENALTY_SLACK))
                        merge(
                            results, entry, score, dist,
                            isExact = false, isCorrection = true, isPrefixMatch = true,
                        )
                    }
                }
            }
        }

        val ranked = results.values.asSequence()
            .filter { allowPossiblyOffensive || !it.entry.isPossiblyOffensive }
            .sortedWith(compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score })
            .toList()
        val top = ranked.take(maxCount).toMutableList()
        // The query isn't a known word: it's likely a typo, so never let completions of the
        // wrong prefix crowd every correction out of the bar — reserve one slot.
        if (query.length >= 3 && top.size >= maxCount &&
            top.none { it.isExactMatch } && top.none { it.isCorrection }
        ) {
            val bestCorrection = ranked.firstOrNull { it.isCorrection }
            if (bestCorrection != null) {
                top.removeAt(top.lastIndex)
                top.add(1.coerceAtMost(top.size), bestCorrection)
            }
        }
        return top
    }

    private fun merge(
        results: LinkedHashMap<String, RankedWord>,
        entry: WordEntry,
        score: Double,
        distance: Double,
        isExact: Boolean,
        isCorrection: Boolean,
        isPrefixMatch: Boolean = false,
    ) {
        val existing = results[entry.word]
        if (existing == null || existing.score < score) {
            results[entry.word] = RankedWord(entry, score, distance, isExact, isCorrection, isPrefixMatch)
        }
    }

    /** First index whose norm is >= [query]. */
    private fun lowerBound(query: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].norm < query) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose norm no longer starts with [query]. */
    private fun upperBoundOfPrefix(query: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val norm = sorted[mid].norm
            if (norm.startsWith(query) || norm < query) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Weighted Damerau-Levenshtein distance with early abandon: insert/delete cost 1.0,
     * substitution costs [ADJACENT_SUBSTITUTION_COST] between physically adjacent keys and 1.0
     * otherwise, adjacent transposition costs [TRANSPOSITION_COST]. Returns a value greater than
     * [maxCost] as soon as the band exceeds it.
     */
    internal fun weightedEditDistance(a: String, b: String, maxCost: Double): Double {
        val la = a.length
        val lb = b.length
        var prevPrev: DoubleArray? = null
        var prev = DoubleArray(lb + 1) { it.toDouble() }
        var curr = DoubleArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i.toDouble()
            var rowMin = curr[0]
            for (j in 1..lb) {
                val chA = a[i - 1]
                val chB = b[j - 1]
                val substCost = when {
                    chA == chB -> 0.0
                    proximity.areAdjacent(chA, chB) -> ADJACENT_SUBSTITUTION_COST
                    else -> 1.0
                }
                var cost = minOf(
                    prev[j] + 1.0,           // deletion
                    curr[j - 1] + 1.0,       // insertion
                    prev[j - 1] + substCost, // substitution
                )
                val pp = prevPrev
                if (pp != null && i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = minOf(cost, pp[j - 2] + TRANSPOSITION_COST)
                }
                curr[j] = cost
                if (cost < rowMin) rowMin = cost
            }
            if (rowMin > maxCost) return maxCost + 1.0 // early abandon: no cell can shrink below rowMin
            val recycled = prevPrev ?: DoubleArray(lb + 1)
            prevPrev = prev
            prev = curr
            curr = recycled
        }
        return prev[lb]
    }

    /**
     * Weighted edit distance between [a] and the best-matching *prefix* of [b]: same cost model
     * as [weightedEditDistance], but the alignment may stop anywhere inside [b] (the unmatched
     * tail is the completion, not an error). Only the first `a.length + maxCost + 1` characters
     * of [b] are considered; early abandon applies unchanged.
     */
    internal fun weightedPrefixDistance(a: String, b: String, maxCost: Double): Double {
        val la = a.length
        val lb = minOf(b.length, la + maxCost.toInt() + 1)
        var prevPrev: DoubleArray? = null
        var prev = DoubleArray(lb + 1) { it.toDouble() }
        var curr = DoubleArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i.toDouble()
            var rowMin = curr[0]
            for (j in 1..lb) {
                val chA = a[i - 1]
                val chB = b[j - 1]
                val substCost = when {
                    chA == chB -> 0.0
                    proximity.areAdjacent(chA, chB) -> ADJACENT_SUBSTITUTION_COST
                    else -> 1.0
                }
                var cost = minOf(
                    prev[j] + 1.0,           // deletion
                    curr[j - 1] + 1.0,       // insertion
                    prev[j - 1] + substCost, // substitution
                )
                val pp = prevPrev
                if (pp != null && i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    cost = minOf(cost, pp[j - 2] + TRANSPOSITION_COST)
                }
                curr[j] = cost
                if (cost < rowMin) rowMin = cost
            }
            if (rowMin > maxCost) return maxCost + 1.0 // early abandon
            val recycled = prevPrev ?: DoubleArray(lb + 1)
            prevPrev = prev
            prev = curr
            curr = recycled
        }
        var best = prev[0]
        for (j in 1..lb) {
            if (prev[j] < best) best = prev[j]
        }
        return best
    }

    companion object {
        /** Exact matches always outrank completions/corrections (max regular score is 255). */
        const val EXACT_MATCH_BASE = 10_000.0
        const val ADJACENT_SUBSTITUTION_COST = 0.45
        const val TRANSPOSITION_COST = 0.6
        const val MIN_FUZZY_QUERY_LENGTH = 2
        /** Fuzzy-prefix completion needs one more character of signal than whole-word fuzzy. */
        const val MIN_FUZZY_PREFIX_QUERY_LENGTH = 3
        /** Higher slack = gentler score penalty per edit, letting corrections compete with
         *  completions of a wrong prefix (was 0.75, which starved corrections structurally). */
        const val CORRECTION_PENALTY_SLACK = 2.0
    }
}
