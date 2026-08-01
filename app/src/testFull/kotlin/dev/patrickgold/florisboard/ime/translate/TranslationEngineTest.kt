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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import java.io.File

/**
 * Runs the REAL bundled translation models on the JVM. The `ai.onnxruntime` API is identical on
 * Android and desktop, so this exercises exactly the code path that ships on device.
 *
 * The expected token ids come from the HuggingFace/Python reference implementation
 * (`ref_translate.py`), which makes the tokenizer port verifiable down to the individual id
 * rather than only "looks about right".
 */
class MarianTokenizerTest : FunSpec({
    val assetsDir = File("src/main/assets-translate/translate")

    fun tokenizer(direction: String): MarianTokenizer =
        File(assetsDir, "$direction/vocab.tsv").bufferedReader().use { MarianTokenizer.fromVocabTsv(it) }

    context("vocabulary loads").config(enabled = assetsDir.exists()) {
        test("ar-en vocab size matches the model") {
            tokenizer("ar-en").vocabSize shouldBe 62834
        }
        test("en-ar vocab size matches the model") {
            tokenizer("en-ar").vocabSize shouldBe 62802
        }
    }

    context("encoding matches the Python reference exactly").config(enabled = assetsDir.exists()) {
        test("ar: مرحبا كيف حالك") {
            tokenizer("ar-en").encode("مرحبا كيف حالك").toList() shouldBe
                listOf(111, 971, 30, 682, 1219, 57, 0)
        }
        test("ar: صباح الخير يا صديقي") {
            tokenizer("ar-en").encode("صباح الخير يا صديقي").toList() shouldBe
                listOf(7006, 6683, 214, 2249, 41, 0)
        }
        test("ar: أين المستشفى من فضلك؟") {
            tokenizer("ar-en").encode("أين المستشفى من فضلك؟").toList() shouldBe
                listOf(136, 88, 11060, 11, 4637, 55, 0)
        }
        test("en: Good morning my friend") {
            tokenizer("en-ar").encode("Good morning my friend").toList() shouldBe
                listOf(1797, 3092, 137, 2791, 0)
        }
        test("en: Where is the hospital please?") {
            tokenizer("en-ar").encode("Where is the hospital please?").toList() shouldBe
                listOf(1249, 33, 3, 6038, 1746, 32, 0)
        }
    }

    context("round trip").config(enabled = assetsDir.exists()) {
        test("decode(encode(x)) recovers the sentence") {
            val tok = tokenizer("en-ar")
            tok.decode(tok.encode("Where is the hospital please?")) shouldBe "Where is the hospital please?"
        }
    }

    context("direction detection") {
        test("Arabic input translates to English") {
            TranslationDirection.detect("مرحبا كيف حالك") shouldBe TranslationDirection.AR_TO_EN
        }
        test("English input translates to Arabic") {
            TranslationDirection.detect("Good morning") shouldBe TranslationDirection.EN_TO_AR
        }
        test("mixed input follows the dominant script") {
            TranslationDirection.detect("مرحبا hello صديقي") shouldBe TranslationDirection.AR_TO_EN
        }
        test("digits only default to English source") {
            TranslationDirection.detect("2026") shouldBe TranslationDirection.EN_TO_AR
        }
    }
})

/**
 * End-to-end neural translation. Tagged as a slow test: it loads ~107 MB of quantized weights per
 * direction and greedily decodes, which takes a few seconds on a desktop CPU.
 */
class OnnxTranslationEngineTest : FunSpec({
    val assetsDir = File("src/main/assets-translate/translate")
    val modelsPresent = File(assetsDir, "ar-en/encoder.onnx").exists() &&
        File(assetsDir, "en-ar/encoder.onnx").exists()

    context("Arabic to English").config(enabled = modelsPresent) {
        test("مرحبا كيف حالك -> greeting in English") {
            OnnxTranslationEngine.open(File(assetsDir, "ar-en")).use { engine ->
                val out = engine.translate("مرحبا كيف حالك")
                println("ar-en: 'مرحبا كيف حالك' -> '$out'")
                out.isNotBlank().shouldBeTrue()
                out shouldContainIgnoringCase "how"
            }
        }
        test("أين المستشفى من فضلك؟ -> mentions hospital") {
            OnnxTranslationEngine.open(File(assetsDir, "ar-en")).use { engine ->
                val out = engine.translate("أين المستشفى من فضلك؟")
                println("ar-en: 'أين المستشفى من فضلك؟' -> '$out'")
                out shouldContainIgnoringCase "hospital"
            }
        }
    }

    context("English to Arabic").config(enabled = modelsPresent) {
        test("Good morning my friend -> Arabic greeting") {
            OnnxTranslationEngine.open(File(assetsDir, "en-ar")).use { engine ->
                val out = engine.translate("Good morning my friend")
                println("en-ar: 'Good morning my friend' -> '$out'")
                out shouldContainIgnoringCase "صباح"
            }
        }
        test("Where is the hospital please? -> Arabic question") {
            OnnxTranslationEngine.open(File(assetsDir, "en-ar")).use { engine ->
                val out = engine.translate("Where is the hospital please?")
                println("en-ar: 'Where is the hospital please?' -> '$out'")
                out shouldContainIgnoringCase "المستشفى"
            }
        }
    }

    context("edge cases").config(enabled = modelsPresent) {
        test("blank input returns blank without touching the model") {
            OnnxTranslationEngine.open(File(assetsDir, "ar-en")).use { engine ->
                engine.translate("   ") shouldBe ""
            }
        }
        test("cancellation stops decoding early") {
            OnnxTranslationEngine.open(File(assetsDir, "ar-en")).use { engine ->
                engine.translate("مرحبا كيف حالك", isCancelled = { true }) shouldBe ""
            }
        }
    }
})
