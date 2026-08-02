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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import java.io.File

/**
 * One loaded per-language dictionary, produced by `utils/build_dictionary.py` and shipped as an
 * APK asset `ime/dict/<language>.sqlite3` (schema v2: `meta(key,value)`,
 * `words(word,norm,freq,flags)`, `bigrams(w1_norm,w2,freq)`, `trigrams(w12_norm,w3,freq)`).
 *
 * The words table is fully materialized into a [WordIndex] at load time (fast in-memory prefix +
 * fuzzy search) — with the selected [DialectOverlay]'s words merged in on top; the bigram and
 * trigram tables stay on disk and are queried per lookup through small LRU caches, because the
 * full tables would be too large to keep on the heap. Overlay phrase chains are merged into the
 * query results at read time.
 */
class SqliteWordDictionary private constructor(
    val language: String,
    val index: WordIndex,
    private val database: SQLiteDatabase,
    /** Which dialect overlay this instance was loaded with ("" = none); compared by the
     *  provider against the current pref to decide when a reload is needed. */
    val overlayKey: String,
    private val overlay: DialectOverlay,
    private val hasTrigramsTable: Boolean,
) {
    private class NgramCache : LinkedHashMap<String, List<Pair<String, Int>>>(
        BIGRAM_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, Int>>>): Boolean {
            return size > BIGRAM_CACHE_SIZE
        }
    }

    private val bigramCache = NgramCache()
    private val trigramCache = NgramCache()

    /** Max-merges two follower lists (already best-first) and re-sorts, best first. */
    private fun mergeFollowers(
        a: List<Pair<String, Int>>,
        b: List<Pair<String, Int>>,
    ): List<Pair<String, Int>> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val merged = LinkedHashMap<String, Int>(a.size + b.size)
        for ((word, freq) in a + b) merged.merge(word, freq, ::maxOf)
        return merged.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun queryFollowers(
        cache: NgramCache,
        sql: String,
        key: String,
        overlayFollowers: List<Pair<String, Int>>,
        maxCount: Int,
        enabled: Boolean,
    ): List<Pair<String, Int>> {
        val fromDb = if (!enabled) emptyList() else run {
            synchronized(cache) { cache[key] } ?: try {
                database.rawQuery(sql, arrayOf(key)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0) to cursor.getInt(1))
                        }
                    }
                }
            } catch (e: SQLiteException) {
                flogError { "ngram query failed for '$key': $e" }
                emptyList()
            }.also { result -> synchronized(cache) { cache[key] = result } }
        }
        return mergeFollowers(fromDb, overlayFollowers).take(maxCount)
    }

    /**
     * Returns the most frequent follower words of the word whose normalized form is [prevNorm],
     * best first, as (display word, freq 0..255) pairs. Overlay phrase chains included.
     */
    fun nextWords(prevNorm: String, maxCount: Int): List<Pair<String, Int>> {
        if (prevNorm.isEmpty()) return emptyList()
        return queryFollowers(
            cache = bigramCache,
            sql = "SELECT w2, freq FROM bigrams WHERE w1_norm = ? ORDER BY freq DESC LIMIT $BIGRAM_FETCH_LIMIT",
            key = prevNorm,
            overlayFollowers = overlay.bigrams[prevNorm] ?: emptyList(),
            maxCount = maxCount,
            enabled = true,
        )
    }

    /**
     * Trigram followers of the two-word context [w12Norm] (= "norm(w1) norm(w2)"), best first.
     * Empty on schema-v1 databases without a trigrams table.
     */
    fun nextWords2(w12Norm: String, maxCount: Int): List<Pair<String, Int>> {
        if (w12Norm.isBlank()) return emptyList()
        return queryFollowers(
            cache = trigramCache,
            sql = "SELECT w3, freq FROM trigrams WHERE w12_norm = ? ORDER BY freq DESC LIMIT $BIGRAM_FETCH_LIMIT",
            key = w12Norm,
            overlayFollowers = overlay.trigrams[w12Norm] ?: emptyList(),
            maxCount = maxCount,
            enabled = hasTrigramsTable,
        )
    }

    fun close() {
        try {
            database.close()
        } catch (e: Exception) {
            flogError { "failed to close dictionary db: $e" }
        }
    }

    companion object {
        private const val BIGRAM_CACHE_SIZE = 256
        private const val BIGRAM_FETCH_LIMIT = 16

        /**
         * Loads the dictionary for [language], staging the asset to the app's files dir first
         * (SQLite cannot open a database directly from inside the APK). Returns null if no
         * dictionary asset exists for this language.
         *
         * The staged copy is placed under `filesDir/ime/dict/` (NOT cacheDir, which FlorisBoard
         * wipes on every startup) and is refreshed whenever the asset size changes, which is our
         * cheap stand-in for a version check between app updates.
         */
        fun load(
            context: Context,
            language: String,
            normalizer: WordNormalizer,
            overlay: DialectOverlay = DialectOverlay.EMPTY,
            overlayKey: String = "",
        ): SqliteWordDictionary? {
            val assetPath = "ime/dict/$language.sqlite3"
            val assetSize = try {
                context.assets.open(assetPath).use { input -> input.available().toLong() }
            } catch (e: Exception) {
                return null // No dictionary shipped for this language.
            }

            val stagedFile = File(context.filesDir, "ime/dict/$language.sqlite3")
            try {
                if (!stagedFile.exists() || stagedFile.length() != assetSize) {
                    stagedFile.parentFile?.mkdirs()
                    context.assets.open(assetPath).use { input ->
                        stagedFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    flogInfo { "staged dictionary asset $assetPath (${assetSize / 1024} KiB)" }
                }
            } catch (e: Exception) {
                flogError { "failed to stage dictionary asset $assetPath: $e" }
                return null
            }

            return try {
                val db = SQLiteDatabase.openDatabase(
                    stagedFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                )
                val entries = db.rawQuery("SELECT word, norm, freq, flags FROM words", null).use { cursor ->
                    buildList(cursor.count) {
                        while (cursor.moveToNext()) {
                            add(WordEntry(
                                word = cursor.getString(0),
                                norm = cursor.getString(1),
                                freq = cursor.getInt(2),
                                flags = cursor.getInt(3),
                            ))
                        }
                    }
                }
                val merged = if (overlay.isEmpty()) entries else mergeOverlayWords(entries, overlay, normalizer)
                val hasTrigrams = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'trigrams'", null,
                ).use { it.moveToFirst() }
                flogInfo {
                    "loaded $language dictionary: ${merged.size} words" +
                        (if (overlayKey.isEmpty()) "" else " (dialect overlay: $overlayKey)")
                }
                SqliteWordDictionary(
                    language = language,
                    index = WordIndex(merged, KeyProximity.forLanguage(language)),
                    database = db,
                    overlayKey = overlayKey,
                    overlay = overlay,
                    hasTrigramsTable = hasTrigrams,
                )
            } catch (e: Exception) {
                flogError { "failed to load dictionary $assetPath: $e" }
                null
            }
        }

        /** Applies the overlay's word boosts on the base entries: existing display words keep
         *  the higher frequency, unknown dialect words are appended as new entries. */
        internal fun mergeOverlayWords(
            entries: List<WordEntry>,
            overlay: DialectOverlay,
            normalizer: WordNormalizer,
        ): List<WordEntry> {
            val byWord = HashMap<String, Int>(entries.size)
            entries.forEachIndexed { i, entry -> byWord[entry.word] = i }
            val merged = entries.toMutableList()
            for ((word, freq) in overlay.wordBoosts) {
                val at = byWord[word]
                if (at != null) {
                    val existing = merged[at]
                    if (existing.freq < freq) merged[at] = existing.copy(freq = freq)
                } else {
                    merged.add(WordEntry(word = word, norm = normalizer.normalize(word), freq = freq))
                }
            }
            return merged
        }
    }
}
