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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

private fun ar(vararg entries: Pair<String, Int>): WordIndex {
    val list = entries.map { (word, freq) ->
        WordEntry(word = word, norm = ArabicNormalizer.normalize(word), freq = freq)
    }
    return WordIndex(list, KeyProximity.ARABIC)
}

class WordIndexTest : FunSpec({

    context("prefix completion") {
        val index = ar(
            "مرحبا" to 250,
            "مرحبتين" to 180,
            "مساء" to 200,
            "مبارح" to 210,
        )

        test("completes by prefix, ranked by frequency") {
            val results = index.suggest("مرح", maxCount = 8, allowPossiblyOffensive = true)
            results.map { it.entry.word }.take(2) shouldBe listOf("مرحبا", "مرحبتين")
        }

        test("no results for unknown prefix") {
            index.suggest("زز", maxCount = 8, allowPossiblyOffensive = true).shouldBeEmpty()
        }
    }

    context("exact match is pinned first even over higher-frequency completions") {
        val index = ar(
            "شو" to 255,
            "شوي" to 254,
            "شوف" to 253,
        )
        test("typing شو ranks شو first") {
            val results = index.suggest("شو", maxCount = 8, allowPossiblyOffensive = true)
            results.first().entry.word shouldBe "شو"
            results.first().isExactMatch.shouldBeTrue()
        }
    }

    context("normalization-driven matching") {
        val index = ar(
            "إسلام" to 220,
            "مدرسة" to 210,
        )
        test("typed without hamza still finds إسلام as exact match") {
            val results = index.suggest(ArabicNormalizer.normalize("اسلام"), 8, true)
            results.first().entry.word shouldBe "إسلام"
            results.first().isExactMatch.shouldBeTrue()
        }
        test("typed with ه finds مدرسة") {
            val results = index.suggest(ArabicNormalizer.normalize("مدرسه"), 8, true)
            results.first().entry.word shouldBe "مدرسة"
        }
    }

    context("fuzzy correction") {
        val index = ar(
            "مرحبا" to 250,
            "كتير" to 240,
            "منيح" to 235,
        )

        test("transposition is corrected: مرحاب -> مرحبا") {
            val results = index.suggest(ArabicNormalizer.normalize("مرحاب"), 8, true)
            results.map { it.entry.word } shouldContain "مرحبا"
        }

        test("adjacent-key substitution is cheap: كتيب vs كتير (ب and ر not adjacent, still dist 1)") {
            val results = index.suggest(ArabicNormalizer.normalize("كتيب"), 8, true)
            results.map { it.entry.word } shouldContain "كتير"
        }

        test("distance respects proximity weighting") {
            val index2 = ar("عمل" to 100)
            // غ is physically adjacent to ع on the Arabic layout -> cheaper than a random letter
            val distAdjacent = index2.weightedEditDistance("غمل", "عمل", 2.0)
            val distFar = index2.weightedEditDistance("قمل", "عمل", 2.0)
            distAdjacent shouldBeLessThan distFar
        }

        test("short queries do not fuzzy match") {
            val results = index.suggest("كت", 8, true)
            results.map { it.entry.word } shouldNotContain "منيح"
        }
    }

    context("offensive filtering") {
        val index = WordIndex(
            listOf(
                WordEntry("عادي", ArabicNormalizer.normalize("عادي"), 200),
                WordEntry("سيئة", ArabicNormalizer.normalize("سيئة"), 200, flags = WordEntry.FLAG_POSSIBLY_OFFENSIVE),
            ),
            KeyProximity.ARABIC,
        )
        test("flagged words are hidden when not allowed") {
            val q = ArabicNormalizer.normalize("سيئة")
            index.suggest(q, 8, allowPossiblyOffensive = false).map { it.entry.word }
                .shouldNotContain("سيئة")
        }
        test("flagged words appear when allowed") {
            val q = ArabicNormalizer.normalize("سيئة")
            index.suggest(q, 8, allowPossiblyOffensive = true).map { it.entry.word }
                .shouldContain("سيئة")
        }
    }

    context("multiple display spellings for one norm") {
        val index = ar(
            "إن" to 240,
            "ان" to 230,
        )
        test("both spellings surface as exact matches for the shared norm") {
            val results = index.suggest("ان", 8, true)
            results.filter { it.isExactMatch }.map { it.entry.word } shouldBe listOf("إن", "ان")
        }
    }

    context("english index") {
        val index = WordIndex(
            listOf(
                WordEntry("hello", "hello", 250),
                WordEntry("help", "help", 230),
                WordEntry("world", "world", 220),
            ),
            KeyProximity.QWERTY,
        )
        test("prefix completion") {
            index.suggest("hel", 8, true).map { it.entry.word }.take(2) shouldBe listOf("hello", "help")
        }
        test("correction: wprld -> world (adjacent o/p)") {
            index.suggest("wprld", 8, true).map { it.entry.word } shouldContain "world"
        }
    }
})

class KeyProximityTest : FunSpec({
    test("Arabic same-row neighbors") {
        KeyProximity.ARABIC.areAdjacent('ع', 'غ').shouldBeTrue()
    }
    test("Arabic cross-row neighbors") {
        KeyProximity.ARABIC.areAdjacent('ق', 'ب').shouldBeTrue()
    }
    test("QWERTY neighbors") {
        KeyProximity.QWERTY.areAdjacent('o', 'p').shouldBeTrue()
    }
    test("distant keys are not adjacent") {
        KeyProximity.QWERTY.areAdjacent('q', 'm') shouldBe false
    }
})
