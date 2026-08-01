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

        // 3. Fuzzy corrections (only worth it once there is enough signal).
        if (query.length >= MIN_FUZZY_QUERY_LENGTH) {
            val maxCost = if (query.length >= 5) 2.0 else 1.0
            val minLen = query.length - maxCost.toInt()
            val maxLen = query.length + maxCost.toInt()
            for (entry in sorted) {
                val len = entry.norm.length
                if (len < minLen || len > maxLen) continue
                if (entry.norm.startsWith(query)) continue // already covered as completion
                val dist = weightedEditDistance(query, entry.norm, maxCost)
                if (dist > maxCost) continue
                val score = entry.freq * (1.0 - dist / (maxCost + 0.75))
                merge(results, entry, score, dist, isExact = false, isCorrection = true)
            }
        }

        return results.values.asSequence()
            .filter { allowPossiblyOffensive || !it.entry.isPossiblyOffensive }
            .sortedWith(compareByDescending<RankedWord> { it.isExactMatch }.thenByDescending { it.score })
            .take(maxCount)
            .toList()
    }

    private fun merge(
        results: LinkedHashMap<String, RankedWord>,
        entry: WordEntry,
        score: Double,
        distance: Double,
        isExact: Boolean,
        isCorrection: Boolean,
    ) {
        val existing = results[entry.word]
        if (existing == null || existing.score < score) {
            results[entry.word] = RankedWord(entry, score, distance, isExact, isCorrection)
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

    companion object {
        /** Exact matches always outrank completions/corrections (max regular score is 255). */
        const val EXACT_MATCH_BASE = 10_000.0
        const val ADJACENT_SUBSTITUTION_COST = 0.45
        const val TRANSPOSITION_COST = 0.6
        const val MIN_FUZZY_QUERY_LENGTH = 3
    }
}
