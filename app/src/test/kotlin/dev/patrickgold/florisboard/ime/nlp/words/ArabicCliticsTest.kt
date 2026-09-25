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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private fun ar(vararg entries: Pair<String, Int>): WordIndex {
    val list = entries.map { (word, freq) ->
        WordEntry(word = word, norm = ArabicNormalizer.normalize(word), freq = freq)
    }
    return WordIndex(list, KeyProximity.ARABIC)
}

private fun clitics(index: WordIndex, composing: String) = ArabicClitics.cliticCandidates(
    index = index,
    composing = composing,
    query = ArabicNormalizer.normalize(composing),
    allowPossiblyOffensive = true,
)

private fun splits(composing: String, vararg indexes: WordIndex) = ArabicClitics.splitCandidates(
    indexes = indexes.toList(),
    composing = composing,
    normalizer = ArabicNormalizer,
    allowPossiblyOffensive = true,
)

class ArabicCliticsTest : FunSpec({

    context("إكمال خلف السوابق — شكوى المالك: «اليوم» محفوظة و«والي» لا تكملها") {
        val index = ar(
            "اليوم" to 200,
            "الكتاب" to 180,
            "بيت" to 150,
        )

        test("«والي» تقترح «واليوم»") {
            val words = clitics(index, "والي").map { it.entry.word }
            words shouldContain "واليوم"
        }

        test("«بالكتا» تقترح «بالكتاب» (سابقة مركبة)") {
            val words = clitics(index, "بالكتا").map { it.entry.word }
            words shouldContain "بالكتاب"
        }

        test("«للبي» تقترح «للبيت»") {
            val words = clitics(index, "للبي").map { it.entry.word }
            words shouldContain "للبيت"
        }

        test("المرشح المسبوق لا يبلغ رتبة المطابقة التامة ولا يلتزم آليًا") {
            val ranked = clitics(index, "واليوم")
            ranked.shouldContain(ranked.first { it.entry.word == "واليوم" })
            ranked.all { !it.isExactMatch && !it.isCorrection }.shouldBeTrue()
        }

        test("باقٍ أقصر من حرفين لا يولّد شيئًا") {
            clitics(index, "وا").shouldBeEmpty()
        }

        test("نص بحركات (طول مطبَّع مختلف) يمتنع بسلام") {
            clitics(index, "وَالي").shouldBeEmpty()
        }
    }

    context("تصحيح الفراغ المستبدَل — شكوى المالك: «و/ة» بدل المسافة تعمي التصحيح") {
        val index = ar(
            "كلمة" to 200,
            "حلوة" to 190,
            "صباح" to 220,
            "الخير" to 230,
        )

        test("«كلمةوحلوة» تقترح «كلمة حلوة»") {
            val results = splits("كلمةوحلوة", index)
            results.map { it.entry.word } shouldContain "كلمة حلوة"
        }

        test("«صباحةالخير» تقترح «صباح الخير» (ة دخيلة)") {
            val results = splits("صباحةالخير", index)
            results.map { it.entry.word } shouldContain "صباح الخير"
        }

        test("الشطر الأيمن يُكمل إن لم يكن تامًا: «كلمةوحلو» → «كلمة حلوة»") {
            val results = splits("كلمةوحلو", index)
            results.map { it.entry.word } shouldContain "كلمة حلوة"
        }

        test("مرشح الفصل تصحيحٌ ممنوع من الالتزام الآلي") {
            val best = splits("كلمةوحلوة", index).first()
            best.isCorrection.shouldBeTrue()
            best.isPrefixMatch.shouldBeTrue()
            best.isExactMatch.shouldBeFalse()
        }

        test("أيسر مجهول = لا فصل") {
            splits("زحلقةوحلوة", index).shouldBeEmpty()
        }

        test("يعمل عبر فهرسين (كلمة المستخدم يسارًا ومعجم عام يمينًا)") {
            val user = ar("قمرية" to 90)
            val static = ar("جميلة" to 150)
            val results = splits("قمريةوجميلة", user, static)
            results.map { it.entry.word } shouldContain "قمرية جميلة"
        }
    }

    context("بذر الفصل في الشريط") {
        val split = RankedWord(
            entry = WordEntry("كلمة حلوة", "كلمه حلوه", 190),
            score = 180.0, distance = 1.0,
            isExactMatch = false, isCorrection = true, isPrefixMatch = true,
        )
        val completion = RankedWord(
            entry = WordEntry("كلمةوحلوةتانية", "كلمهوحلوهتانيه", 60),
            score = 40.0, distance = 0.0,
            isExactMatch = false, isCorrection = false,
        )

        test("بلا مطابقة تامة: الفصل يُبذر في الموضع الثاني") {
            val out = ArabicClitics.seedSplit(listOf(completion, completion, completion), listOf(split), 8)
            out[1].entry.word shouldBe "كلمة حلوة"
        }

        test("مع مطابقة تامة للملتحمة: الفصل يُعرض دون انتزاع الصدارة") {
            val exact = completion.copy(isExactMatch = true)
            val out = ArabicClitics.seedSplit(listOf(exact, completion), listOf(split), 8)
            out.first().isExactMatch.shouldBeTrue()
            out.map { it.entry.word } shouldContain "كلمة حلوة"
        }
    }
})
