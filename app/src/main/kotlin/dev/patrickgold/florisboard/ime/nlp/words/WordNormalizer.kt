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

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes words into a canonical *matching key*. The key is only ever used for lookups inside
 * the suggestion engine — the original spelling from the dictionary is what gets displayed and
 * committed. The exact same normalization rules are re-implemented in `utils/build_dictionary.py`,
 * which fills the `norm` column of the dictionary databases; the two implementations MUST stay in
 * sync, and `ArabicNormalizerTest` pins the behavior with golden cases.
 */
interface WordNormalizer {
    /** Normalizes [word] into its matching key. */
    fun normalize(word: String): String

    companion object {
        /**
         * Returns the normalizer for an ISO 639-1 [language] code. Unknown languages get the
         * Latin/generic normalizer, which is a reasonable default for most alphabetic scripts.
         */
        fun forLanguage(language: String): WordNormalizer = when (language) {
            "ar" -> ArabicNormalizer
            else -> LatinNormalizer
        }
    }
}

/**
 * Normalizer for Arabic script following the folding rules commonly used by Arabic IR systems,
 * chosen so that the spellings Syrians actually type match the canonical dictionary forms:
 *
 * - harakat/tanween/shadda/sukun (U+064B..U+065F), superscript alef (U+0670) and Quranic
 *   annotation marks (U+06D6..U+06ED) are stripped;
 * - tatweel (U+0640) is removed;
 * - alef variants أ إ آ ٱ fold to bare ا (typing "اسلام" must find "إسلام");
 * - teh marbuta ة folds to ه (chat spelling frequently swaps them);
 * - alef maqsura ى folds to ي;
 * - hamza carriers ؤ / ئ fold to و / ي (bare ء is kept — it is its own letter position);
 * - Arabic-Indic digits (both sets) fold to ASCII digits.
 */
object ArabicNormalizer : WordNormalizer {
    override fun normalize(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            when (ch) {
                // Strip: tatweel, harakat, superscript alef, Quranic marks
                'ـ' -> {}
                in 'ً'..'ٟ' -> {}
                'ٰ' -> {}
                in 'ۖ'..'ۭ' -> {}
                // Fold alef variants
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                // Fold teh marbuta and alef maqsura
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                // Fold hamza carriers
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                // Fold Arabic-Indic digits (both the ٠٩ and the ۰۹ sets)
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/**
 * Normalizer for Latin (and generic alphabetic) scripts: lowercases and strips combining
 * diacritical marks after NFD decomposition, so "Cafe" matches "café" and vice versa.
 */
object LatinNormalizer : WordNormalizer {
    private val combiningMarks = Regex("\\p{Mn}+")

    override fun normalize(word: String): String {
        val lowered = word.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "")
    }
}
