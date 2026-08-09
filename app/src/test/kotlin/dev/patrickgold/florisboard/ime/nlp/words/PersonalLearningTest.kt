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
            merged.first().entry.word shouldBe "كرمال"
        }

        test("user completions get the priority floor over any static non-exact score") {
            // A word the user actually typed, completing the current prefix, must beat even a
            // max-frequency static completion — «اقترح من قاموسي الذي تعلمته أولاً».
            val static = listOf(ranked("كتير", 255, score = 255.0))
            val user = listOf(ranked("كرمالك", 112, score = 60.0))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.first().entry.word shouldBe "كرمالك"
            merged.first().score shouldBe PersonalLearning.USER_PRIORITY_FLOOR + 60.0
        }

        test("user CORRECTIONS are never floored — the autocommit margin gate must stay honest") {
            // If a one-edit user correction were floored to 256+, the relative margin gate in the
            // provider would compare 256+ against 0..255 static scores and auto-replace correctly
            // typed unknown words (e.g. «rani» → learned «Rami»). Boost yes, floor no.
            val static = listOf(ranked("كتير", 255, score = 255.0))
            val user = listOf(ranked("كرمالك", 112, score = 60.0, correction = true))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.first().entry.word shouldBe "كتير" // user: 60 * 2.0 = 120 < 255
            merged.first { it.entry.word == "كرمالك" }.score shouldBe 60.0 * PersonalLearning.USER_SCORE_BOOST
        }

        test("a correction survives the merge even when completions fill every slot") {
            val static = listOf(
                ranked("نرحل", 250, score = 250.0),
                ranked("نرحب", 249, score = 249.0),
                ranked("نرحلها", 248, score = 248.0),
                ranked("مرحبا", 200, score = 100.0, correction = true),
            )
            val merged = PersonalLearning.mergeRanked(static, emptyList(), emptySet(), 3)
            merged.map { it.entry.word } shouldContain "مرحبا"
        }

        test("duplicate keeps the better variant") {
            val static = listOf(ranked("منيح", 240, score = 240.0))
            val user = listOf(ranked("منيح", 130, score = 130.0))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.size shouldBe 1
            // The user's own copy now carries the priority floor, so it is the better variant.
            merged.first().score shouldBe PersonalLearning.USER_PRIORITY_FLOOR + 130.0
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

    context("n-gram prediction boosts") {
        test("longer context wins: quadgram > trigram > raw bigram freq") {
            val raw = listOf("وبركاته" to 180)
            val tri = PersonalLearning.boostTrigramPredictions(raw).first().second
            val quad = PersonalLearning.boostQuadgramPredictions(raw).first().second
            (quad > tri).shouldBeTrue()
            (tri > 180).shouldBeTrue()
        }
        test("boosts cap at 255") {
            PersonalLearning.boostQuadgramPredictions(listOf("الله" to 250)).first().second shouldBe 255
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

    context("trigram context blending") {
        test("trigram followers outweigh bigram ones for the same word") {
            val blended = PersonalLearning.blendFollowers(
                bigrams = mapOf("الله" to 100, "النور" to 240),
                trigrams = mapOf("الله" to 150),
            )
            blended["الله"] shouldBe (150 * PersonalLearning.TRIGRAM_FOLLOWER_BOOST).toInt()
            blended["النور"] shouldBe 240 // untouched
        }
        test("boost caps at 255") {
            PersonalLearning.blendFollowers(
                bigrams = emptyMap(),
                trigrams = mapOf("الله" to 250),
            )["الله"] shouldBe 255
            PersonalLearning.boostTrigramPredictions(listOf("الله" to 250))
                .first().second shouldBe 255
        }
        test("empty trigram map returns the bigram map unchanged") {
            val bigrams = mapOf("الخير" to 200)
            PersonalLearning.blendFollowers(bigrams, emptyMap()) shouldBe bigrams
        }
    }

    context("extractLastWords") {
        test("extracts up to two trailing words, oldest first") {
            PersonalLearning.extractLastWords("قال إن شاء ", 2) shouldBe listOf("إن", "شاء")
            PersonalLearning.extractLastWords("شاء ", 2) shouldBe listOf("شاء")
        }
        test("punctuation closes the context") {
            PersonalLearning.extractLastWords("مرحبا، كيف ", 2) shouldBe listOf("كيف")
            PersonalLearning.extractLastWords("مرحبا، ", 2) shouldBe emptyList()
            PersonalLearning.extractLastWords("hello. how ", 2) shouldBe listOf("how")
        }
        test("empty and whitespace-only input") {
            PersonalLearning.extractLastWords("", 2) shouldBe emptyList()
            PersonalLearning.extractLastWords("   ", 2) shouldBe emptyList()
        }
        test("harakat are part of the word, never a context breaker") {
            PersonalLearning.extractLastWords("مُحمد ", 2) shouldBe listOf("مُحمد")
            PersonalLearning.extractLastWords("إن شاءَ ", 2) shouldBe listOf("إن", "شاءَ")
            // The norm key still matches the mined static trigram ("ان شاء" -> "الله").
            ArabicNormalizer.normalize("شاءَ") shouldBe "شاء"
        }
        test("bidi format marks are transparent separators") {
            PersonalLearning.extractLastWords("مرحبا‏ كيف ", 2) shouldBe listOf("مرحبا", "كيف")
        }
    }
})
