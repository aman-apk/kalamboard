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
 * Physical key adjacency for the fuzzy matcher: substituting a character with a neighboring key is
 * a much more likely typo than substituting a distant one, so it gets a cheaper edit cost.
 *
 * The rows below mirror the bundled layouts (`characters/arabic.json` and `characters/qwerty.json`).
 * Row characters are passed through the language's [WordNormalizer] when the map is built, because
 * the matcher always operates on normalized keys (e.g. the physical ة key contributes its
 * normalized form ه to the map).
 */
class KeyProximity private constructor(private val neighbors: Map<Char, Set<Char>>) {

    /** True if [a] and [b] are the same key or physically adjacent keys. */
    fun areAdjacent(a: Char, b: Char): Boolean {
        if (a == b) return true
        return neighbors[a]?.contains(b) == true
    }

    companion object {
        // Keep in sync with app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/arabic.json
        private val ARABIC_ROWS = listOf(
            "ضصثقفغعهخحج",
            "شسيبلاتنمكط",
            "ذءؤرىةوزظد",
        )

        // Keep in sync with .../layouts/characters/qwerty.json
        private val QWERTY_ROWS = listOf(
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
        )

        private fun build(rows: List<String>, normalizer: WordNormalizer): KeyProximity {
            val map = mutableMapOf<Char, MutableSet<Char>>()
            fun norm(ch: Char): Char {
                val n = normalizer.normalize(ch.toString())
                return if (n.length == 1) n[0] else ch
            }
            fun connect(a: Char, b: Char) {
                if (a == b) return
                map.getOrPut(a) { mutableSetOf() }.add(b)
                map.getOrPut(b) { mutableSetOf() }.add(a)
            }
            for ((rowIndex, row) in rows.withIndex()) {
                for ((i, rawCh) in row.withIndex()) {
                    val ch = norm(rawCh)
                    // Same-row neighbor
                    if (i + 1 < row.length) connect(ch, norm(row[i + 1]))
                    // Row below: keys at index i-1, i and i+1 are all reachable by a slipped finger
                    if (rowIndex + 1 < rows.size) {
                        val below = rows[rowIndex + 1]
                        for (j in (i - 1)..(i + 1)) {
                            below.getOrNull(j)?.let { connect(ch, norm(it)) }
                        }
                    }
                }
            }
            return KeyProximity(map)
        }

        val ARABIC by lazy { build(ARABIC_ROWS, ArabicNormalizer) }
        val QWERTY by lazy { build(QWERTY_ROWS, LatinNormalizer) }

        /** Returns the proximity map for an ISO 639-1 [language] code. */
        fun forLanguage(language: String): KeyProximity = when (language) {
            "ar" -> ARABIC
            else -> QWERTY
        }
    }
}
