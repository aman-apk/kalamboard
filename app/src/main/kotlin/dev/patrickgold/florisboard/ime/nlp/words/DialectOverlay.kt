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
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo

/**
 * The user-selectable Arabic dialect layer. The base `ar.sqlite3` dictionary is pure MSA
 * (فصحى); the selected dialect's curated lexicon is applied on top at dictionary load time
 * as a [DialectOverlay]. `LEVANTINE` is the default (KalamBoard's home dialect).
 */
enum class ArabicDialect(val overlayKey: String?) {
    NONE(null),
    LEVANTINE("levantine"),
    EGYPTIAN("egyptian"),
    GULF("gulf"),
    IRAQI("iraqi");
}

/**
 * One parsed dialect lexicon, applied on top of the static MSA dictionary at load time.
 *
 * Shipped as TSV assets `ime/dict/overlays/<language>_<key>.tsv` (merged from the curated
 * category files under `dictionaries/<dialect>/`), format `text<TAB>freq(1-255)`, `#` comments.
 * Parsing and phrase decomposition mirror `load_overlays()` in `utils/build_dictionary.py` and
 * MUST stay in sync with it: a single word boosts/adds a word entry; a multi-word phrase
 * additionally contributes a high-frequency bigram chain (and trigram chain for 3+ words), which
 * is how «يعطيك العافية» completes itself word by word.
 */
class DialectOverlay private constructor(
    /** display word -> freq (1..255) */
    val wordBoosts: Map<String, Int>,
    /** norm(w1) -> followers (display word, freq), best first */
    val bigrams: Map<String, List<Pair<String, Int>>>,
    /** "norm(w1) norm(w2)" -> followers (display word, freq), best first */
    val trigrams: Map<String, List<Pair<String, Int>>>,
) {
    fun isEmpty(): Boolean = wordBoosts.isEmpty() && bigrams.isEmpty() && trigrams.isEmpty()

    /** Merges [other] on top of this overlay: word boosts keep the max freq, follower lists are
     *  unioned per context with max freq and re-sorted best first. */
    fun mergedWith(other: DialectOverlay): DialectOverlay {
        if (other.isEmpty()) return this
        if (this.isEmpty()) return other
        val boosts = HashMap(wordBoosts)
        for ((word, freq) in other.wordBoosts) boosts.merge(word, freq, ::maxOf)
        fun mergeFollowers(
            a: Map<String, List<Pair<String, Int>>>,
            b: Map<String, List<Pair<String, Int>>>,
        ): Map<String, List<Pair<String, Int>>> {
            val out = HashMap<String, HashMap<String, Int>>(a.size + b.size)
            for (source in arrayOf(a, b)) {
                for ((context, followers) in source) {
                    val target = out.getOrPut(context) { HashMap() }
                    for ((word, freq) in followers) target.merge(word, freq, ::maxOf)
                }
            }
            return out.mapValues { (_, followers) ->
                followers.entries.sortedByDescending { it.value }.map { it.key to it.value }
            }
        }
        return DialectOverlay(boosts, mergeFollowers(bigrams, other.bigrams), mergeFollowers(trigrams, other.trigrams))
    }

    companion object {
        val EMPTY = DialectOverlay(emptyMap(), emptyMap(), emptyMap())

        /** Mirror of `is_valid_word("ar", norm)` in the build tool: Arabic letters only, 2..24. */
        private fun isValidArabicNorm(norm: String): Boolean {
            return norm.length in 2..24 && norm.all { it in 'ء'..'ي' }
        }

        fun parse(lines: Sequence<String>, normalizer: WordNormalizer): DialectOverlay {
            val boosts = HashMap<String, Int>()
            val bigrams = HashMap<String, HashMap<String, Int>>()
            val trigrams = HashMap<String, HashMap<String, Int>>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split("\t")
                if (parts.size != 2) continue
                val freq = parts[1].trim().toIntOrNull()?.coerceIn(1, 255) ?: continue
                val tokens = parts[0].split(' ').filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val norms = tokens.map { normalizer.normalize(it) }
                if (!norms.all { isValidArabicNorm(it) }) continue
                for (token in tokens) {
                    boosts.merge(token, freq, ::maxOf)
                }
                for (i in 0 until tokens.size - 1) {
                    bigrams.getOrPut(norms[i]) { HashMap() }.merge(tokens[i + 1], freq, ::maxOf)
                }
                for (i in 0 until tokens.size - 2) {
                    trigrams.getOrPut("${norms[i]} ${norms[i + 1]}") { HashMap() }
                        .merge(tokens[i + 2], freq, ::maxOf)
                }
            }
            fun sortFollowers(map: Map<String, Map<String, Int>>): Map<String, List<Pair<String, Int>>> =
                map.mapValues { (_, followers) ->
                    followers.entries.sortedByDescending { it.value }.map { it.key to it.value }
                }
            return DialectOverlay(boosts, sortFollowers(bigrams), sortFollowers(trigrams))
        }

        /**
         * Loads the overlay for [dialect]: the pan-Arabic `ar_common.tsv` formulas (applied for
         * EVERY dialect setting, including فصحى/NONE — religious/greeting formulas are not
         * dialectal) merged with the selected dialect's own lexicon, if any.
         */
        fun loadFromAssets(context: Context, language: String, dialect: ArabicDialect): DialectOverlay {
            if (language != "ar") return EMPTY
            val common = loadOne(context, "ime/dict/overlays/${language}_common.tsv", language)
            val key = dialect.overlayKey ?: return common
            val dialectOverlay = loadOne(context, "ime/dict/overlays/${language}_$key.tsv", language)
            return common.mergedWith(dialectOverlay)
        }

        private fun loadOne(context: Context, assetPath: String, language: String): DialectOverlay {
            return try {
                val overlay = context.assets.open(assetPath).bufferedReader().useLines { lines ->
                    parse(lines, WordNormalizer.forLanguage(language))
                }
                flogInfo { "loaded dialect overlay $assetPath: ${overlay.wordBoosts.size} words" }
                overlay
            } catch (e: Exception) {
                flogError { "failed to load dialect overlay $assetPath: $e" }
                EMPTY
            }
        }
    }
}
