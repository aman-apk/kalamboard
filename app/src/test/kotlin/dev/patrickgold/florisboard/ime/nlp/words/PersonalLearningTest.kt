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

        test("a user-learned correction beats a static correction at equal edit distance") {
            // Both are one-edit corrections with the same frequency and base score: the word
            // learned from the user's own typing must recover first — the typo goes back to the
            // nearest word of the USER'S dictionary, not just the static lexicon.
            val static = listOf(ranked("كتير", 200, score = 150.0, correction = true))
            val user = listOf(ranked("كبير", 200, score = 150.0, correction = true))
            val merged = PersonalLearning.mergeRanked(static, user, emptySet(), 8)
            merged.first().entry.word shouldBe "كبير"
            merged.map { it.entry.word } shouldContain "كتير"
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
        test("four-word contexts extract for pentagram learning") {
            PersonalLearning.extractLastWords("السلام عليكم ورحمة الله ", 4) shouldBe
                listOf("السلام", "عليكم", "ورحمة", "الله")
        }
    }

    context("recency weight") {
        val day = 24L * 60 * 60 * 1000
        val now = 100L * day
        test("this week outranks last month, stale habits fade") {
            PersonalLearning.recencyWeight(now, now - day) shouldBe 1.25
            PersonalLearning.recencyWeight(now, now - 5 * day) shouldBe 1.15
            PersonalLearning.recencyWeight(now, now - 20 * day) shouldBe 1.0
            PersonalLearning.recencyWeight(now, now - 90 * day) shouldBe 0.85
        }
        test("missing or future timestamps stay neutral") {
            PersonalLearning.recencyWeight(now, 0L) shouldBe 1.0
            PersonalLearning.recencyWeight(now, now + day) shouldBe 1.0
        }
    }

    context("blended next-word merge") {
        test("agreement across orders beats a single loud order") {
            // «الله» is predicted by two orders at 150 each; «الخير» by one order at 160.
            val merged = PersonalLearning.mergeNextWordsBlended(
                orders = listOf(
                    listOf("الله" to 150, "الخير" to 160),
                    listOf("الله" to 150),
                ),
                blocked = emptySet(),
                maxCount = 5,
            )
            // base 150 + 0.12*150 = 168 > 160 — the agreed-upon word wins.
            merged.first().first shouldBe "الله"
            merged.first().second shouldBe 168
        }
        test("blocked words are dropped and the cap holds") {
            val merged = PersonalLearning.mergeNextWordsBlended(
                orders = listOf(listOf("سيئة" to 250, "طيبة" to 100)),
                blocked = setOf("سيئة"),
                maxCount = 5,
            )
            merged.map { it.first } shouldBe listOf("طيبة")
            val capped = PersonalLearning.mergeNextWordsBlended(
                orders = listOf(listOf("كلمة" to 255), listOf("كلمة" to 255, "أخرى" to 255)),
                blocked = emptySet(),
                maxCount = 5,
            )
            capped.first().second shouldBe 255
        }
    }

    context("phrase chain steps") {
        test("a strong dominant follower extends the chain") {
            PersonalLearning.chainStep(listOf("الله" to 200, "الناس" to 90)) shouldBe "الله"
            PersonalLearning.chainStep(listOf("وبركاته" to 120)) shouldBe "وبركاته"
        }
        test("weak or contested followers end the chain") {
            // Below CHAIN_MIN_LINK_FREQ.
            PersonalLearning.chainStep(listOf("ربما" to 80)) shouldBe null
            // A fork: the runner-up is too close.
            PersonalLearning.chainStep(listOf("خير" to 150, "نور" to 140)) shouldBe null
            PersonalLearning.chainStep(emptyList()) shouldBe null
        }
        test("a once-seen personal habit is not chain-strong, a twice-seen one is") {
            // count=1 -> freq 84 < 96; count=2 -> freq 108 >= 96: the chain only trusts
            // what the user actually repeated.
            (PersonalLearning.userBigramFreq(1) < PersonalLearning.CHAIN_MIN_LINK_FREQ).shouldBeTrue()
            (PersonalLearning.userBigramFreq(2) >= PersonalLearning.CHAIN_MIN_LINK_FREQ).shouldBeTrue()
        }
    }

    context("context-endorsed completion seeding (سيناريو «إن ← ش»)") {
        test("a predicted follower leads the bar once its first letter is typed") {
            // بعد «إن» تنبأ المحرك بـ«شاء/شالله» — كتابة «ش» يجب ألا تغرقهما تحت شادي وشايفة.
            val frequencyRanked = listOf(
                ranked("شادي", 200, 180.0),
                ranked("شايفة", 190, 170.0),
                ranked("شز", 180, 160.0),
            )
            val followers = mapOf(
                "شاء" to ("شاء" to 220),
                "شالله" to ("شالله" to 150),
                "يكون" to ("يكون" to 140),
            )
            val seeded = PersonalLearning.seedContextCompletions(frequencyRanked, followers, "ش", 5)
            seeded[0].entry.word shouldBe "شاء"
            seeded[1].entry.word shouldBe "شالله"
            seeded.map { it.entry.word } shouldContain "شادي"
            seeded.map { it.entry.word } shouldNotContain "يكون"
        }
        test("an exact match is never displaced by a follower") {
            val exact = ranked("شاي", 100, 10100.0, exact = true)
            val followers = mapOf("شايك" to ("شايك" to 255))
            val seeded = PersonalLearning.seedContextCompletions(listOf(exact), followers, "شاي", 5)
            seeded[0].entry.word shouldBe "شاي"
        }
        test("an existing candidate is lifted in place, not duplicated") {
            val frequencyRanked = listOf(ranked("شادي", 200, 180.0), ranked("شاء", 90, 60.0))
            val followers = mapOf("شاء" to ("شاء" to 200))
            val seeded = PersonalLearning.seedContextCompletions(frequencyRanked, followers, "ش", 5)
            seeded[0].entry.word shouldBe "شاء"
            seeded.count { it.entry.word == "شاء" } shouldBe 1
        }
        test("no followers or empty query passes through untouched") {
            val base = listOf(ranked("كلمة", 100, 90.0))
            PersonalLearning.seedContextCompletions(base, emptyMap(), "ك", 5) shouldBe base
            PersonalLearning.seedContextCompletions(
                base, mapOf("س" to ("س" to 1)), "", 5,
            ) shouldBe base
        }
    }

    context("sentence-start key") {
        test("the sentinel can never collide with normalized text") {
            ArabicNormalizer.normalize("<s>") shouldBe PersonalLearning.SENTENCE_START_KEY
            // No learnable word ever normalizes INTO the sentinel: '<' is not a letter.
            PersonalLearning.isLearnableWord(PersonalLearning.SENTENCE_START_KEY).shouldBeFalse()
        }
    }
})
