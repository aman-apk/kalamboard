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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

/** Pins the TSV parsing + phrase decomposition contract shared with `load_overlays()` in
 *  `utils/build_dictionary.py` — change one side, change both. */
class DialectOverlayTest : FunSpec({

    val normalizer = WordNormalizer.forLanguage("ar")

    fun parse(vararg lines: String) = DialectOverlay.parse(lines.asSequence(), normalizer)

    test("single word becomes a boost, comments and blanks are skipped") {
        val overlay = parse("# comment", "", "بدي\t250")
        overlay.wordBoosts shouldBe mapOf("بدي" to 250)
        overlay.bigrams.isEmpty().shouldBeTrue()
    }

    test("two-word phrase decomposes into word boosts + a bigram chain") {
        val overlay = parse("يعطيك العافية\t250")
        overlay.wordBoosts.keys shouldBe setOf("يعطيك", "العافية")
        overlay.bigrams[normalizer.normalize("يعطيك")]!!.first() shouldBe ("العافية" to 250)
        overlay.trigrams.isEmpty().shouldBeTrue()
    }

    test("three-word phrase additionally yields a trigram chain") {
        val overlay = parse("ان شاء الله\t248")
        val w12 = "${normalizer.normalize("ان")} ${normalizer.normalize("شاء")}"
        overlay.trigrams[w12]!!.first() shouldBe ("الله" to 248)
        // And the sliding bigram windows exist too.
        overlay.bigrams[normalizer.normalize("شاء")]!!.map { it.first } shouldContain "الله"
    }

    test("malformed lines and invalid tokens are dropped, freq is clamped") {
        val overlay = parse(
            "بلا تردد",             // no tab -> dropped
            "kalam\t200",           // Latin token -> dropped for ar
            "ب\t200",               // single letter after normalization -> dropped
            "منيح\t999",            // freq clamped to 255
        )
        overlay.wordBoosts shouldBe mapOf("منيح" to 255)
        overlay.wordBoosts shouldNotContainKey "kalam"
    }

    test("duplicate entries keep the max freq") {
        val overlay = parse("هلق\t200", "هلق\t240")
        overlay.wordBoosts["هلق"] shouldBe 240
    }

    test("mergeOverlayWords boosts existing words and appends new ones") {
        val base = listOf(
            WordEntry("مرحبا", normalizer.normalize("مرحبا"), 200),
            WordEntry("بيت", normalizer.normalize("بيت"), 180),
        )
        val overlay = parse("مرحبا\t255", "هلق\t240", "بيت\t100")
        val merged = SqliteWordDictionary.mergeOverlayWords(base, overlay, normalizer)
        merged.first { it.word == "مرحبا" }.freq shouldBe 255 // boosted
        merged.first { it.word == "بيت" }.freq shouldBe 180   // overlay lower -> untouched
        merged.first { it.word == "هلق" }.freq shouldBe 240   // appended
        merged.size shouldBe 3
    }
})
