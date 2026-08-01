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

import java.io.BufferedReader

/**
 * SentencePiece **Unigram** tokenizer for the bundled Marian / OPUS-MT translation models.
 *
 * This is a faithful port of the pipeline described by the model's `tokenizer.json`:
 * `WhitespaceSplit` → `Metaspace(▁, add_prefix_space)` → Unigram Viterbi → append `</s>`.
 * The models ship with `precompiled_charsmap: null`, i.e. **no** unicode normalization step,
 * which is why none is implemented here.
 *
 * The vocabulary is loaded from a compact `vocab.tsv` (`piece<TAB>score` per line, index = id)
 * generated from the original `tokenizer.json` — parsing 62k JSON entries on every keyboard
 * start would be needlessly slow.
 *
 * Deliberately free of any Android dependency so the whole tokenizer is unit-testable on the JVM
 * against reference token ids produced by the Python/HuggingFace implementation.
 */
class MarianTokenizer(
    private val pieces: List<String>,
    private val scores: FloatArray,
) {
    private val pieceToId: Map<String, Int> = buildMap(pieces.size) {
        // First occurrence wins, mirroring SentencePiece behavior for duplicate pieces.
        for ((index, piece) in pieces.withIndex()) putIfAbsent(piece, index)
    }
    private val maxPieceLength: Int = pieces.maxOf { it.length }

    val vocabSize: Int get() = pieces.size

    /** Encodes [text] into model input ids, terminated by [EOS_ID]. */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(text.length / 2 + 2)
        for (word in text.trim().split(WHITESPACE_REGEX)) {
            if (word.isEmpty()) continue
            viterbiInto(METASPACE + word, ids)
        }
        ids.add(EOS_ID)
        return ids.toIntArray()
    }

    /** Turns generated ids back into text, undoing the metaspace substitution. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == EOS_ID || id == UNK_ID || id < 0 || id >= pieces.size) continue
            sb.append(pieces[id])
        }
        return sb.toString().replace(METASPACE, ' ').trim()
    }

    /**
     * Best segmentation of [word] under the unigram language model: a simple Viterbi over
     * character positions maximizing the summed log-scores of the chosen pieces. Positions that
     * no known piece can reach fall back to a heavily penalized single-character `<unk>`, so the
     * search can never dead-end on unseen characters.
     */
    private fun viterbiInto(word: String, out: MutableList<Int>) {
        val n = word.length
        val best = DoubleArray(n + 1) { NEG_INF }
        val backPos = IntArray(n + 1) { -1 }
        val backId = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] <= NEG_INF_GUARD) continue
            val maxEnd = minOf(n, i + maxPieceLength)
            for (j in i + 1..maxEnd) {
                val id = pieceToId[word.substring(i, j)] ?: continue
                val score = best[i] + scores[id]
                if (score > best[j]) {
                    best[j] = score
                    backPos[j] = i
                    backId[j] = id
                }
            }
            // Unknown single character fallback.
            val j = i + 1
            val unkScore = best[i] + UNK_PENALTY
            if (unkScore > best[j]) {
                best[j] = unkScore
                backPos[j] = i
                backId[j] = UNK_ID
            }
        }
        val reversed = ArrayList<Int>(8)
        var pos = n
        while (pos > 0) {
            val prev = backPos[pos]
            if (prev < 0) { // Should be unreachable thanks to the unk fallback.
                out.add(UNK_ID)
                return
            }
            reversed.add(backId[pos])
            pos = prev
        }
        for (k in reversed.indices.reversed()) out.add(reversed[k])
    }

    companion object {
        const val EOS_ID = 0
        const val UNK_ID = 1
        const val METASPACE = '▁'

        private const val UNK_PENALTY = -10.0
        private const val NEG_INF = -1.0e18
        private const val NEG_INF_GUARD = -1.0e17
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** Parses the compact `piece<TAB>score` vocabulary produced by `tools/build_translate_assets.py`. */
        fun fromVocabTsv(reader: BufferedReader): MarianTokenizer {
            val pieces = ArrayList<String>(64_000)
            val scores = ArrayList<Float>(64_000)
            reader.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) return@forEachLine
                pieces.add(unescape(line.substring(0, tab)))
                scores.add(line.substring(tab + 1).toFloatOrNull() ?: 0f)
            }
            return MarianTokenizer(pieces, scores.toFloatArray())
        }

        private fun unescape(raw: String): String {
            if (!raw.contains('\\')) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        't' -> { sb.append('\t'); i += 2; continue }
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
