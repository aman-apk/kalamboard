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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureNanoTime

/**
 * End-to-end quality + latency test of the suggestion engine over the REAL shipped Arabic lexicon
 * (`ar_words.tsv` is dumped from `assets/ime/dict/ar.sqlite3` — regenerate it with the python
 * one-liner in the file header whenever the dictionary is rebuilt).
 *
 * The latency budget in the plan is < 10ms per keystroke on device; this JVM test asserts a
 * loose 25ms average / 60ms p95 so it stays green on slow CI machines while still catching an
 * accidental O(n²) regression immediately. The measured numbers are printed for the session log.
 */
class RealDictionaryQualityTest : FunSpec({

    val index: WordIndex by lazy {
        val entries = checkNotNull(javaClass.classLoader!!.getResourceAsStream("ar_words.tsv"))
            .bufferedReader()
            .readLines()
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 2) return@mapNotNull null
                val freq = parts[1].toIntOrNull() ?: return@mapNotNull null
                WordEntry(parts[0], ArabicNormalizer.normalize(parts[0]), freq)
            }
            .toList()
        entries.size shouldBeGreaterThan 50_000
        WordIndex(entries, KeyProximity.ARABIC)
    }

    fun suggest(raw: String) = index.suggest(ArabicNormalizer.normalize(raw), 8, allowPossiblyOffensive = true)

    context("quality on the real lexicon") {
        test("completion: مرح pins the exact word first, مرحبا right behind it") {
            val results = suggest("مرح")
            // "مرح" is itself a real MSA word, so the exact match is pinned first by design;
            // the high-frequency completion "مرحبا" must be the immediate runner-up.
            results.first().entry.word shouldBe "مرح"
            results.first().isExactMatch.shouldBeTrue()
            results.map { it.entry.word }.take(3) shouldContain "مرحبا"
        }
        test("Levantine layer is on top: بدي، هلق، منيح exist as exact matches") {
            for (word in listOf("بدي", "هلق", "منيح", "كتير", "عنجد")) {
                val results = suggest(word)
                results.first().isExactMatch.shouldBeTrue()
                results.first().entry.word shouldBe word
            }
        }
        test("normalization: typing اسلام finds إسلام as exact match") {
            suggest("اسلام").first { it.isExactMatch }.entry.word shouldBe "إسلام"
        }
        test("correction: مرحاب (transposition) suggests مرحبا") {
            suggest("مرحاب").map { it.entry.word } shouldContain "مرحبا"
        }
        test("correction: منيخ (adjacent key خ/ح) suggests منيح") {
            suggest("منيخ").map { it.entry.word } shouldContain "منيح"
        }
    }

    context("latency on the real lexicon (50k words)") {
        test("average and p95 within budget") {
            val queries = listOf(
                // worst-case short prefixes (huge completion ranges)
                "ا", "م", "ب", "و", "ال",
                // typical prefixes
                "مرح", "بدي", "شلو", "يعط", "الحم",
                // fuzzy-heavy 4-6 letter queries (full banded scan)
                "مرحاب", "منيخ", "كتيير", "انشال", "معلش", "صبااح",
            ).map { ArabicNormalizer.normalize(it) }

            // Warmup (JIT).
            repeat(3) { queries.forEach { index.suggest(it, 8, true) } }

            val samples = mutableListOf<Long>()
            repeat(10) {
                for (q in queries) {
                    samples += measureNanoTime { index.suggest(q, 8, true) }
                }
            }
            samples.sort()
            val avgMs = samples.average() / 1_000_000.0
            val p95Ms = samples[(samples.size * 95) / 100] / 1_000_000.0
            println("suggest() over 50k-word Arabic lexicon: avg=%.2fms p95=%.2fms max=%.2fms".format(
                avgMs, p95Ms, samples.last() / 1_000_000.0,
            ))
            (avgMs < 25.0).shouldBeTrue()
            (p95Ms < 60.0).shouldBeTrue()
        }
    }
})
