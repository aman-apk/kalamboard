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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.LongBuffer

/** Translation direction, matching the asset folder names under `assets/translate/`. */
enum class TranslationDirection(val id: String, val sourceLanguage: String, val targetLanguage: String) {
    AR_TO_EN("ar-en", "ar", "en"),
    EN_TO_AR("en-ar", "en", "ar");

    fun reversed() = if (this == AR_TO_EN) EN_TO_AR else AR_TO_EN

    companion object {
        /**
         * Picks a direction from the script actually used in [text]: any Arabic letter means the
         * user is writing Arabic and wants English out, otherwise the other way round.
         */
        fun detect(text: String): TranslationDirection {
            val arabic = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }
            val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
            return if (arabic >= latin && arabic > 0) AR_TO_EN else EN_TO_AR
        }
    }
}

/**
 * Fully on-device neural machine translation over the bundled quantized OPUS-MT (Marian) models,
 * executed with ONNX Runtime. Nothing here touches the network: the models are shipped inside the
 * APK and staged to the app's private files dir on first use.
 *
 * Decoding is plain greedy search re-running the decoder over the whole prefix each step. The
 * exported graphs expose `present.*` KV outputs but take no `past.*` inputs, so caching is not
 * available without a differently exported model; for keyboard-length inputs the quadratic cost is
 * acceptable and keeps the implementation small and verifiable.
 *
 * Android-free by construction (paths in, string out) so it is exercised by real JVM unit tests
 * against the same models that ship on device — see `OnnxTranslationEngineTest`.
 */
class OnnxTranslationEngine(
    private val tokenizer: MarianTokenizer,
    private val encoderSession: OrtSession,
    private val decoderSession: OrtSession,
    private val env: OrtEnvironment,
    private val decoderStartTokenId: Int,
) : Closeable {

    /**
     * Translates [text], generating at most [maxNewTokens] tokens. Returns an empty string for
     * blank input. [isCancelled] is polled between decoding steps so a newer request can abandon
     * an in-flight translation.
     */
    fun translate(
        text: String,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        isCancelled: () -> Boolean = { false },
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val inputIds = tokenizer.encode(trimmed)
        // Output length tracks input length closely for this model family. Bounding it avoids
        // paying for 96 decoder passes on a three-word phrase (each pass re-reads the whole
        // prefix, so the tail steps are the expensive ones).
        val effectiveMaxNewTokens = minOf(maxNewTokens, inputIds.size * 2 + 8)
        val sourceLength = inputIds.size.toLong()
        val inputIdsLong = LongArray(inputIds.size) { inputIds[it].toLong() }
        val attentionMask = LongArray(inputIds.size) { 1L }

        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIdsLong), longArrayOf(1, sourceLength)).use { inputTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, sourceLength)).use { maskTensor ->
                val encoderOut = encoderSession.run(
                    mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor),
                )
                encoderOut.use {
                    val hidden = it.get(0) as OnnxTensor
                    return decodeGreedy(hidden, maskTensor, effectiveMaxNewTokens, isCancelled)
                }
            }
        }
    }

    private fun decodeGreedy(
        encoderHidden: OnnxTensor,
        encoderMask: OnnxTensor,
        maxNewTokens: Int,
        isCancelled: () -> Boolean,
    ): String {
        val generated = ArrayList<Int>(maxNewTokens)
        val prefix = ArrayList<Long>(maxNewTokens + 1)
        prefix.add(decoderStartTokenId.toLong())

        repeat(maxNewTokens) {
            if (isCancelled()) return tokenizer.decode(generated.toIntArray())
            val prefixArray = LongArray(prefix.size) { i -> prefix[i] }
            OnnxTensor.createTensor(
                env, LongBuffer.wrap(prefixArray), longArrayOf(1, prefixArray.size.toLong()),
            ).use { decoderInput ->
                val outputs = decoderSession.run(
                    mapOf(
                        "encoder_attention_mask" to encoderMask,
                        "input_ids" to decoderInput,
                        "encoder_hidden_states" to encoderHidden,
                    ),
                    setOf("logits"),
                )
                val next = outputs.use { result ->
                    val logits = result.get(0) as OnnxTensor
                    argmaxOfLastPosition(logits)
                }
                if (next == MarianTokenizer.EOS_ID) return tokenizer.decode(generated.toIntArray())
                generated.add(next)
                prefix.add(next.toLong())
            }
        }
        return tokenizer.decode(generated.toIntArray())
    }

    /** Reads only the final position's row out of the `[1, seq, vocab]` logits tensor. */
    private fun argmaxOfLastPosition(logits: OnnxTensor): Int {
        val shape = logits.info.shape // [batch, decoder_seq, vocab]
        val seqLength = shape[1].toInt()
        val vocabSize = shape[2].toInt()
        val buffer = logits.floatBuffer
        val offset = (seqLength - 1) * vocabSize
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val value = buffer.get(offset + i)
            if (value > bestValue) {
                bestValue = value
                bestIndex = i
            }
        }
        return bestIndex
    }

    override fun close() {
        runCatching { encoderSession.close() }
        runCatching { decoderSession.close() }
    }

    companion object {
        const val DEFAULT_MAX_NEW_TOKENS = 96

        /** Translation is a short user-initiated burst, so use the little cores too (capped at 4). */
        val DEFAULT_THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /**
         * Opens an engine over a staged model directory containing `encoder.onnx`, `decoder.onnx`,
         * `vocab.tsv` and `model_config.json`.
         */
        fun open(
            modelDir: java.io.File,
            env: OrtEnvironment = OrtEnvironment.getEnvironment(),
            threads: Int = DEFAULT_THREADS,
        ): OnnxTranslationEngine {
            val tokenizer = java.io.File(modelDir, "vocab.tsv").bufferedReader().use {
                MarianTokenizer.fromVocabTsv(it)
            }
            val configText = java.io.File(modelDir, "model_config.json").readText()
            val decoderStartTokenId = Regex("\"decoder_start_token_id\"\\s*:\\s*(\\d+)")
                .find(configText)?.groupValues?.get(1)?.toInt()
                ?: error("model_config.json is missing decoder_start_token_id")
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val encoder = env.createSession(java.io.File(modelDir, "encoder.onnx").absolutePath, options)
            val decoder = env.createSession(java.io.File(modelDir, "decoder.onnx").absolutePath, options)
            return OnnxTranslationEngine(tokenizer, encoder, decoder, env, decoderStartTokenId)
        }
    }
}
