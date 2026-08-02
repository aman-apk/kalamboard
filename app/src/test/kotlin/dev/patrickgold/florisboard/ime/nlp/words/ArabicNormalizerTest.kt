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
import io.kotest.matchers.shouldBe

/**
 * Golden cases pinning the Arabic normalization rules. `utils/build_dictionary.py` re-implements
 * the exact same rules in Python (its self-test mirrors this table); if a case changes here it
 * MUST change there too, otherwise engine lookups and dictionary `norm` columns drift apart.
 */
class ArabicNormalizerTest : FunSpec({

    context("alef variants fold to bare alef") {
        listOf(
            "أحمد" to "احمد",
            "إسلام" to "اسلام",
            "آمين" to "امين",
            "ٱلله" to "الله",
        ).forEach { (input, expected) ->
            test("$input -> $expected") {
                ArabicNormalizer.normalize(input) shouldBe expected
            }
        }
    }

    context("teh marbuta and alef maqsura fold") {
        listOf(
            "مدرسة" to "مدرسه",
            "حياة" to "حياه",
            "مصطفى" to "مصطفي",
            "على" to "علي",
        ).forEach { (input, expected) ->
            test("$input -> $expected") {
                ArabicNormalizer.normalize(input) shouldBe expected
            }
        }
    }

    context("hamza carriers fold, bare hamza kept") {
        listOf(
            "مسؤول" to "مسوول",
            "رئيس" to "رييس",
            "شيء" to "شيء",
        ).forEach { (input, expected) ->
            test("$input -> $expected") {
                ArabicNormalizer.normalize(input) shouldBe expected
            }
        }
    }

    context("harakat, shadda and tatweel are stripped") {
        listOf(
            "مُحَمَّد" to "محمد",
            "الحمدُ لِلَّه" to "الحمد لله",
            "مـــرحبا" to "مرحبا",
            "قُرْآن" to "قران",
        ).forEach { (input, expected) ->
            test("$input -> $expected") {
                ArabicNormalizer.normalize(input) shouldBe expected
            }
        }
    }

    context("digits fold to ASCII") {
        test("Arabic-Indic digits") {
            ArabicNormalizer.normalize("سنة ٢٠٢٦") shouldBe "سنه 2026"
        }
        test("extended Arabic-Indic digits") {
            ArabicNormalizer.normalize("۱۲۳") shouldBe "123"
        }
    }

    context("already-normalized text is unchanged") {
        listOf("شو", "هلق", "منيح", "بدي", "كتير").forEach { word ->
            test(word) {
                ArabicNormalizer.normalize(word) shouldBe word
            }
        }
    }

    context("chat spellings meet dictionary spellings") {
        test("typed 'انشالله' matches dictionary 'انشالله' after both normalize") {
            ArabicNormalizer.normalize("انشالله") shouldBe ArabicNormalizer.normalize("إنشالله")
        }
        test("typed 'اسلام' equals normalized 'إسلام'") {
            ArabicNormalizer.normalize("اسلام") shouldBe ArabicNormalizer.normalize("إسلام")
        }
        test("typed 'مدرسه' equals normalized 'مدرسة'") {
            ArabicNormalizer.normalize("مدرسه") shouldBe ArabicNormalizer.normalize("مدرسة")
        }
    }
})

class LatinNormalizerTest : FunSpec({
    test("lowercases") {
        LatinNormalizer.normalize("Hello") shouldBe "hello"
    }
    test("strips accents") {
        LatinNormalizer.normalize("café") shouldBe "cafe"
    }
    test("plain word unchanged") {
        LatinNormalizer.normalize("keyboard") shouldBe "keyboard"
    }
    test("French accents fold, apostrophe kept") {
        LatinNormalizer.normalize("Été") shouldBe "ete"
        LatinNormalizer.normalize("français") shouldBe "francais"
        LatinNormalizer.normalize("l'école") shouldBe "l'ecole"
    }
    test("Turkish dotted/dotless i and cedilla letters fold") {
        LatinNormalizer.normalize("İstanbul") shouldBe "istanbul"
        LatinNormalizer.normalize("ışık") shouldBe "isik"
        LatinNormalizer.normalize("Işık") shouldBe "isik"
        LatinNormalizer.normalize("çocuğu") shouldBe "cocugu"
        LatinNormalizer.normalize("şükür") shouldBe "sukur"
    }
})
