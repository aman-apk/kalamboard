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
 * APK asset `ime/dict/<language>.sqlite3` (schema: `meta(key,value)`, `words(word,norm,freq,flags)`,
 * `bigrams(w1_norm,w2,freq)`).
 *
 * The words table is fully materialized into a [WordIndex] at load time (fast in-memory prefix +
 * fuzzy search); the bigrams table stays on disk and is queried per lookup through a small LRU
 * cache, because a full bigram table would be too large to keep on the heap.
 */
class SqliteWordDictionary private constructor(
    val language: String,
    val index: WordIndex,
    private val database: SQLiteDatabase,
) {
    private val bigramCache = object : LinkedHashMap<String, List<Pair<String, Int>>>(
        BIGRAM_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, Int>>>): Boolean {
            return size > BIGRAM_CACHE_SIZE
        }
    }

    /**
     * Returns the most frequent follower words of the word whose normalized form is [prevNorm],
     * best first, as (display word, freq 0..255) pairs.
     */
    fun nextWords(prevNorm: String, maxCount: Int): List<Pair<String, Int>> {
        if (prevNorm.isEmpty()) return emptyList()
        synchronized(bigramCache) { bigramCache[prevNorm] }?.let { cached ->
            return cached.take(maxCount)
        }
        val result = try {
            database.rawQuery(
                "SELECT w2, freq FROM bigrams WHERE w1_norm = ? ORDER BY freq DESC LIMIT $BIGRAM_FETCH_LIMIT",
                arrayOf(prevNorm),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to cursor.getInt(1))
                    }
                }
            }
        } catch (e: SQLiteException) {
            flogError { "bigram query failed for '$prevNorm': $e" }
            emptyList()
        }
        synchronized(bigramCache) { bigramCache[prevNorm] = result }
        return result.take(maxCount)
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
        fun load(context: Context, language: String, normalizer: WordNormalizer): SqliteWordDictionary? {
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
                flogInfo { "loaded $language dictionary: ${entries.size} words" }
                SqliteWordDictionary(
                    language = language,
                    index = WordIndex(entries, KeyProximity.forLanguage(language)),
                    database = db,
                )
            } catch (e: Exception) {
                flogError { "failed to load dictionary $assetPath: $e" }
                null
            }
        }
    }
}
