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

/**
 * معالجتان عربيتان فوق [WordIndex]، نقيتان بلا أي اعتماد على أندرويد فتُختبران على الـJVM:
 *
 * 1. **إكمال خلف السوابق** ([cliticCandidates]): العربية تُلصق حروف العطف والجر والتعريف
 *    بالكلمة («واليوم» = و + اليوم)، والمعجم — لا سيما كلمات المستخدم المتعلمة — يخزن الكلمة
 *    مجردة. الفهرس وحده يعمى عن «اليوم» فور كتابة «والي»؛ هنا تُقشر السابقة ويُكمل الباقي
 *    ثم تُعاد السابقة إلى صدر كل مرشح.
 *
 * 2. **تصحيح الفراغ المستبدَل** ([splitCandidates]): جوار مفتاح المسافة يجعل «و» و«ة» تحلّان
 *    محلها خطأً فتلتحم كلمتان («كلمةوحلوة»). يُجرَّب كل موضع لهذين الحرفين: إن كان ما قبله
 *    كلمةً معروفة وما بعده كلمةً معروفة أو بادئة واحدة، عُرضت الكلمتان مفصولتين بمسافة.
 */
object ArabicClitics {

    /**
     * السوابق المجرّبة على النص المطبَّع، الأطول أولًا كي لا تسرق «و» قسمة «وال».
     * (التطبيع يطوي أ/إ/آ إلى ا، فتكفي الصور المجردة.)
     */
    private val PREFIXES = listOf(
        "وبال", "فبال", "ولل", "فلل", "وال", "فال", "بال", "كال",
        "لل", "ال", "وب", "فب", "ول", "فل", "وك", "فك",
        "و", "ف", "ب", "ل", "ك",
    )

    /** حرفا الالتصاق اللذان يقعان بدل المسافة (جوار مفتاحها في الصفوف السفلى). */
    private val SPACE_SLIP_CHARS = charArrayOf('و', 'ة')

    /** خصم درجة المرشح المسبوق كي لا يزاحم الإكمال المباشر على قدم المساواة. */
    const val CLITIC_SCORE_DAMPEN = 0.85

    /** معامل درجة مرشح الفصل؛ يقاس على تردد أضعف الكلمتين. */
    const val SPLIT_SCORE_FACTOR = 0.95

    /** أقصى مرشحات إكمالٍ تُؤخذ من كل قسمة سابقة. */
    private const val PER_PREFIX_TAKE = 3

    /** أقصى مرشحات فصلٍ تُنتَج لكلمة ملتحمة واحدة. */
    private const val MAX_SPLIT_CANDIDATES = 2

    /** أقصى طول ملتحمة يُجرَّب فصلها — أبعد من ذلك ليس خطأ مسافة واحدًا. */
    private const val MAX_SPLIT_SCAN = 24

    /**
     * إكمالات خلف سابقة ملصقة: «والي» → قشر «و» → إكمال «الي» = اليوم → «واليوم».
     *
     * [composing] النص الخام كما كُتب و[query] صورته المطبَّعة؛ يُشترط تساوي طوليهما كي يصح
     * اقتطاع السابقة الخام بطول السابقة المطبَّعة (التطبيع لا يغير الأطوال إلا بحذف الحركات،
     * وعندها نمتنع بسلام). تُعاد قائمة [RankedWord] بكلمات مركبة (السابقة + كلمة المعجم).
     */
    fun cliticCandidates(
        index: WordIndex,
        composing: String,
        query: String,
        allowPossiblyOffensive: Boolean,
    ): List<RankedWord> {
        if (index.size == 0) return emptyList()
        if (composing.length != query.length) return emptyList()
        val out = ArrayList<RankedWord>(PER_PREFIX_TAKE * 2)
        val seen = HashSet<String>()
        for (prefix in PREFIXES) {
            if (query.length <= prefix.length + 1) continue // الباقي حرفان فأكثر
            if (!query.startsWith(prefix)) continue
            val rawPrefix = composing.substring(0, prefix.length)
            val rest = query.substring(prefix.length)
            val restRanked = index.suggest(rest, PER_PREFIX_TAKE, allowPossiblyOffensive)
            for (ranked in restRanked) {
                // التصحيحات الغامضة خلف سابقة = تخمين فوق تخمين — الإكمالات والمطابقات فقط.
                if (ranked.isCorrection) continue
                val word = rawPrefix + ranked.entry.word
                if (!seen.add(word)) continue
                // المطابقة التامة للباقي («واليوم» وقد كتب «واليوم» كاملة) ليست «تامة» للمركب
                // في عين الشريط — لكنها أقوى من الإكمال، والخصم يحفظ الترتيب بينهما.
                out += RankedWord(
                    entry = WordEntry(
                        word = word,
                        norm = prefix + ranked.entry.norm,
                        freq = ranked.entry.freq,
                        flags = ranked.entry.flags,
                    ),
                    score = ranked.score * CLITIC_SCORE_DAMPEN,
                    distance = 0.0,
                    isExactMatch = false,
                    isCorrection = false,
                    isPrefixMatch = ranked.isPrefixMatch,
                )
            }
        }
        return out
    }

    /**
     * تصحيح الفراغ المستبدَل: لكل «و»/«ة» متوسطةٍ في [composing] تُجرَّب القسمة حولها —
     * الأيسر كلمة معروفة تمامًا، والأيمن معروف تمامًا أو يُكمل لأقرب كلمة. يُعاد مرشح
     * «الأيسر الأيمن» بمسافة، موسومًا تصحيحًا ممنوعًا من الالتزام الآلي (isPrefixMatch).
     *
     * تُستدعى بفهرسي المستخدم والمعجم معًا كي تُرى كلمات المستخدم في الشطرين سواء.
     */
    fun splitCandidates(
        indexes: List<WordIndex>,
        composing: String,
        normalizer: WordNormalizer,
        allowPossiblyOffensive: Boolean,
    ): List<RankedWord> {
        if (indexes.all { it.size == 0 }) return emptyList()
        val len = minOf(composing.length, MAX_SPLIT_SCAN)
        if (len < 5) return emptyList() // ٢ + فاصل + ٢ على الأقل
        val out = ArrayList<RankedWord>(MAX_SPLIT_CANDIDATES)
        val seen = HashSet<String>()
        for (i in 2..len - 3) {
            val ch = composing[i]
            if (ch != SPACE_SLIP_CHARS[0] && ch != SPACE_SLIP_CHARS[1]) continue
            val left = composing.substring(0, i)
            val right = composing.substring(i + 1)
            val leftNorm = normalizer.normalize(left)
            if (leftNorm.isEmpty()) continue
            val leftFreq = indexes.maxOf { idx -> idx.exact(leftNorm).maxOfOrNull { it.freq } ?: 0 }
            if (leftFreq == 0) continue // الأيسر لا بد كلمة تامة معروفة
            val rightNorm = normalizer.normalize(right)
            if (rightNorm.isEmpty()) continue
            // الأيمن: مطابقة تامة أولى، وإلا أفضل إكمالٍ صِرف (لا تصحيحات — تخمين فوق تخمين).
            var rightWord: String? = null
            var rightFreq = 0
            var rightExact = false
            for (idx in indexes) {
                idx.exact(rightNorm).maxByOrNull { it.freq }?.let {
                    if (!rightExact || it.freq > rightFreq) {
                        rightWord = right // ما كتبه هو عين المقصود
                        rightFreq = it.freq
                        rightExact = true
                    }
                }
            }
            if (!rightExact && right.length >= 2) {
                for (idx in indexes) {
                    val best = idx.suggest(rightNorm, 2, allowPossiblyOffensive)
                        .firstOrNull { !it.isCorrection && !it.isExactMatch }
                    if (best != null && best.entry.freq > rightFreq) {
                        rightWord = best.entry.word
                        rightFreq = best.entry.freq
                    }
                }
            }
            val resolvedRight = rightWord ?: continue
            val text = "$left $resolvedRight"
            if (!seen.add(text)) continue
            val freq = minOf(leftFreq, rightFreq)
            out += RankedWord(
                entry = WordEntry(
                    word = text,
                    norm = "$leftNorm ${normalizer.normalize(resolvedRight)}",
                    freq = freq,
                ),
                // كلمتان تامتان معروفتان أوثق من تامة + إكمال.
                score = freq * SPLIT_SCORE_FACTOR * (if (rightExact) 1.15 else 1.0),
                distance = 1.0,
                isExactMatch = false,
                isCorrection = true,
                isPrefixMatch = true, // لا التزام آليًا أبدًا — ذيلٌ لم يُكتب كله
            )
            if (out.size >= MAX_SPLIT_CANDIDATES) break
        }
        return out.sortedByDescending { it.score }
    }

    /**
     * يضمن لمرشح الفصل الأقوى مقعدًا في الشريط وإن زاحمته الإكمالات — في الموضع الثاني،
     * كما تفعل بذرة السياق — ما لم تكن الكلمة الملتحمة نفسها معروفة (عندها هي المقصودة غالبًا
     * والفصل يبقى متاحًا أدنى القائمة إن بلغها بدرجته).
     */
    fun seedSplit(
        ranked: List<RankedWord>,
        splits: List<RankedWord>,
        maxCount: Int,
    ): List<RankedWord> {
        if (splits.isEmpty()) return ranked
        val best = splits.first()
        if (ranked.any { it.entry.word == best.entry.word }) return ranked
        if (ranked.any { it.isExactMatch }) {
            // ملتحمة معروفة («كلمةوصل» قد تكون مقصودة): الفصل يُعرض دون انتزاع الصدارة.
            val out = ranked.toMutableList()
            out.add(minOf(2, out.size), best)
            return if (out.size > maxCount) out.subList(0, maxCount) else out
        }
        val out = ranked.toMutableList()
        out.add(minOf(1, out.size), best)
        return if (out.size > maxCount) out.subList(0, maxCount) else out
    }
}
