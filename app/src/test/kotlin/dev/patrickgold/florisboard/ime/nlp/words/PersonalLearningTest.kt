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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

private fun ranked(word: String, freq: Int, score: Double, exact: Boolean = false, correction: Boolean = false) =
    RankedWord(
        entry = WordEntry(word, ArabicNormalizer.normalize(word), freq),
        score = score,
        distance = if (correction) 1.0 else 0.0,
        isExactMatch = exact,
        isCorrection = correction,
    )

class PersonalLearningTest : FunSpec({

    context("word shape eligibility") {
        test("normal Arabic and Latin words are learnable") {
            PersonalLearning.isLearnableWord("مرحبا").shouldBeTrue()
            PersonalLearning.isLearnableWord("hello").shouldBeTrue()
            PersonalLearning.isLearnableWord("don't").shouldBeTrue()
        }
        test("junk is not learnable") {
            PersonalLearning.isLearnableWord("").shouldBeFalse()
            PersonalLearning.isLearnableWord("a").shouldBeFalse()
            PersonalLearning.isLearnableWord("مرحبا123").shouldBeFalse()
            PersonalLearning.isLearnableWord("hey!").shouldBeFalse()
            PersonalLearning.isLearnableWord("x".repeat(25)).shouldBeFalse()
        }
    }

    context("frequency arithmetic") {
        test("bump adds and caps at 255") {
            PersonalLearning.bumpedFreq(112) shouldBe 120
            PersonalLearning.bumpedFreq(250) shouldBe 255
            PersonalLearning.bumpedFreq(255) shouldBe 255
        }
        test("personal bigram frequency scales and caps") {
            PersonalLearning.userBigramFreq(1) shouldBe 84
            PersonalLearning.userBigramFreq(5) shouldBe 180
            PersonalLearning.userBigramFreq(100) shouldBe 255
        }
    }

    context("mergeRanked") {
        test("user words are boosted over equal-score static words") {
            val static = listOf(ranked("كتير", 200, score = 200.0))
            val user = listOf(ranked("كرمال", 200, score = 200.0))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.first().entry.word shouldBe "كرمال" // 200 * 1.3 > 200
        }

        test("duplicate keeps the better variant") {
            val static = listOf(ranked("منيح", 240, score = 240.0))
            val user = listOf(ranked("منيح", 130, score = 130.0))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.size shouldBe 1
            merged.first().score shouldBe 240.0
        }

        test("exact match stays pinned above boosted user completions") {
            val static = listOf(ranked("شو", 255, score = WordIndex.EXACT_MATCH_BASE + 255, exact = true))
            val user = listOf(ranked("شوية", 255, score = 255.0))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.first().entry.word shouldBe "شو"
        }

        test("blocked words are dropped from every source") {
            val static = listOf(ranked("بلوك", 250, score = 250.0), ranked("عادي", 100, score = 100.0))
            val user = listOf(ranked("بلوك", 250, score = 250.0))
            val merged = PersonalLearning.mergeRanked(static, user, setOf("بلوك"), 8)
            merged.map { it.entry.word } shouldNotContain "بلوك"
            merged.map { it.entry.word } shouldContain "عادي"
        }

        test("respects maxCount") {
            val static = (1..10).map { ranked("كلمة$it".replace("1", "ا"), 100, score = 100.0 + it) }
            PersonalLearning.mergeRanked(static, emptyList(), emptySet(), 3).size shouldBe 3
        }
    }

    context("rerankByContext") {
        test("a known follower overtakes a slightly better stray candidate") {
            val ranked = listOf(
                ranked("الخيار", 200, score = 200.0, correction = true),
                ranked("الخير", 190, score = 190.0, correction = true),
            )
            val followers = mapOf(ArabicNormalizer.normalize("الخير") to 250)
            val out = PersonalLearning.rerankByContext(ranked, followers, ArabicNormalizer)
            out.first().entry.word shouldBe "الخير" // 190 * (1 + 0.6*250/255) ≈ 302 > 200
        }
        test("exact match stays pinned first even when a follower is boosted") {
            val ranked = listOf(
                ranked("شو", 255, score = WordIndex.EXACT_MATCH_BASE + 255, exact = true),
                ranked("شوي", 200, score = 200.0),
            )
            val followers = mapOf(ArabicNormalizer.normalize("شوي") to 255)
            PersonalLearning.rerankByContext(ranked, followers, ArabicNormalizer)
                .first().entry.word shouldBe "شو"
        }
        test("no followers means untouched order") {
            val ranked = listOf(
                ranked("كلمه", 100, score = 100.0),
                ranked("كلام", 90, score = 90.0),
            )
            PersonalLearning.rerankByContext(ranked, emptyMap(), ArabicNormalizer) shouldBe ranked
        }
    }

    context("mergeNextWords") {
        test("personal pairs merge with static ones, best freq wins") {
            val static = listOf("الخير" to 250, "النور" to 240)
            val personal = listOf("الغالي" to 200, "الخير" to 100)
            val merged = PersonalLearning.mergeNextWords(static, personal, emptySet(), 8)
            merged.first() shouldBe ("الخير" to 250)
            merged.map { it.first } shouldContain "الغالي"
        }
        test("blocked words are dropped") {
            val merged = PersonalLearning.mergeNextWords(
                static = listOf("سيء" to 250),
                personal = emptyList(),
                blocked = setOf("سيء"),
                maxCount = 8,
            )
            merged.map { it.first } shouldNotContain "سيء"
        }
    }
})
