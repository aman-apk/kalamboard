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
import android.database.sqlite.SQLiteOpenHelper
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-device persistence for the personal-learning metadata that does NOT belong in the
 * user-visible Floris user dictionary:
 *
 * - `pending_words`: unknown words the user typed, with a counter — once a word is seen
 *   [PersonalLearning.PENDING_THRESHOLD] times it graduates into the Floris user dictionary
 *   (where the user can see, edit, export and delete it);
 * - `user_bigrams`: the user's personal word-pair counts, merged into next-word prediction;
 * - `blocked_words`: suggestions the user removed via long-press — never suggested again;
 * - `autocorrect_reverts`: words whose auto-correction the user undid with backspace — after
 *   [PersonalLearning.AUTOCORRECT_BLOCK_THRESHOLD] reverts the engine stops auto-committing to
 *   that word (it stays available as a tappable suggestion).
 *
 * Privacy: lives in the app's private database dir, which phase 1's `data_extraction_rules.xml`
 * excludes from both cloud backup and device-to-device transfer. All writes are additionally
 * gated behind the incognito flag and the `suggestion__learn_from_typing` pref by the caller.
 */
class LearningStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val blockedCache = mutableMapOf<String, MutableSet<String>>()
    private var autocorrectRevertCache: MutableMap<String, Int>? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_words (lang TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (lang, word))")
        db.execSQL("CREATE TABLE user_bigrams (lang TEXT NOT NULL, w1_norm TEXT NOT NULL, w2 TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (lang, w1_norm, w2))")
        db.execSQL("CREATE TABLE blocked_words (lang TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY (lang, word))")
        db.execSQL("CREATE TABLE autocorrect_reverts (word TEXT NOT NULL PRIMARY KEY, count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — nothing to migrate yet.
    }

    // --- pending words ---------------------------------------------------------------------------

    /** Increments the seen-counter for an unknown [word] and returns the new count. */
    @Synchronized
    fun recordPendingWord(lang: String, word: String): Int = runSafely(0) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO pending_words (lang, word, count) VALUES (?, ?, 0)",
            arrayOf(lang, word),
        )
        db.execSQL(
            "UPDATE pending_words SET count = count + 1 WHERE lang = ? AND word = ?",
            arrayOf(lang, word),
        )
        db.rawQuery(
            "SELECT count FROM pending_words WHERE lang = ? AND word = ?",
            arrayOf(lang, word),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    @Synchronized
    fun clearPendingWord(lang: String, word: String) = runSafely(Unit) {
        writableDatabase.execSQL(
            "DELETE FROM pending_words WHERE lang = ? AND word = ?",
            arrayOf(lang, word),
        )
    }

    // --- personal bigrams ------------------------------------------------------------------------

    @Synchronized
    fun recordBigram(lang: String, w1Norm: String, w2: String) = runSafely(Unit) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO user_bigrams (lang, w1_norm, w2, count) VALUES (?, ?, ?, 0)",
            arrayOf(lang, w1Norm, w2),
        )
        db.execSQL(
            "UPDATE user_bigrams SET count = MIN(count + 1, 10000) WHERE lang = ? AND w1_norm = ? AND w2 = ?",
            arrayOf(lang, w1Norm, w2),
        )
    }

    /** Personal followers of [w1Norm], best first, as (word, rawCount) pairs. */
    @Synchronized
    fun bigramsFor(lang: String, w1Norm: String, maxCount: Int): List<Pair<String, Int>> = runSafely(emptyList()) {
        readableDatabase.rawQuery(
            "SELECT w2, count FROM user_bigrams WHERE lang = ? AND w1_norm = ? ORDER BY count DESC LIMIT $maxCount",
            arrayOf(lang, w1Norm),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getInt(1))
                }
            }
        }
    }

    // --- user blocklist (long-press remove) ------------------------------------------------------

    @Synchronized
    fun blockWord(lang: String, word: String) = runSafely(Unit) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO blocked_words (lang, word) VALUES (?, ?)",
            arrayOf(lang, word),
        )
        blockedCache.getOrPut(lang) { mutableSetOf() }.add(word)
    }

    @Synchronized
    fun blockedWords(lang: String): Set<String> {
        blockedCache[lang]?.let { return it }
        val loaded = runSafely(mutableSetOf()) {
            readableDatabase.rawQuery(
                "SELECT word FROM blocked_words WHERE lang = ?",
                arrayOf(lang),
            ).use { cursor ->
                val set = mutableSetOf<String>()
                while (cursor.moveToNext()) set.add(cursor.getString(0))
                set
            }
        }
        blockedCache[lang] = loaded
        return loaded
    }

    fun isWordBlocked(lang: String, word: String): Boolean = word in blockedWords(lang)

    // --- autocorrect reverts ---------------------------------------------------------------------

    @Synchronized
    fun recordAutocorrectRevert(word: String) = runSafely(Unit) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO autocorrect_reverts (word, count) VALUES (?, 0)", arrayOf(word))
        db.execSQL("UPDATE autocorrect_reverts SET count = count + 1 WHERE word = ?", arrayOf(word))
        autocorrectRevertCache?.merge(word, 1, Int::plus)
    }

    @Synchronized
    fun isAutocorrectBlocked(word: String): Boolean {
        val cache = autocorrectRevertCache ?: runSafely(mutableMapOf<String, Int>()) {
            readableDatabase.rawQuery("SELECT word, count FROM autocorrect_reverts", null).use { cursor ->
                val map = mutableMapOf<String, Int>()
                while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getInt(1)
                map
            }
        }.also { autocorrectRevertCache = it }
        return (cache[word] ?: 0) >= PersonalLearning.AUTOCORRECT_BLOCK_THRESHOLD
    }

    // --- decay -----------------------------------------------------------------------------------

    /**
     * Gentle periodic forgetting so stale one-off entries don't accumulate forever:
     * weekly for pending-word counters, monthly for personal bigrams (count * 3/4, dropped at 0).
     * Graduated user-dictionary words are user-visible data and are never decayed automatically.
     */
    @Synchronized
    fun decayIfDue(nowMs: Long) = runSafely(Unit) {
        val db = writableDatabase
        if (nowMs - getMetaLong(db, "last_pending_decay") > PENDING_DECAY_INTERVAL_MS) {
            db.execSQL("UPDATE pending_words SET count = (count * 3) / 4")
            db.execSQL("DELETE FROM pending_words WHERE count <= 0")
            setMetaLong(db, "last_pending_decay", nowMs)
        }
        if (nowMs - getMetaLong(db, "last_bigram_decay") > BIGRAM_DECAY_INTERVAL_MS) {
            db.execSQL("UPDATE user_bigrams SET count = (count * 3) / 4")
            db.execSQL("DELETE FROM user_bigrams WHERE count <= 0")
            setMetaLong(db, "last_bigram_decay", nowMs)
        }
    }

    private fun getMetaLong(db: SQLiteDatabase, key: String): Long {
        return db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
        }
    }

    private fun setMetaLong(db: SQLiteDatabase, key: String, value: Long) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value.toString()))
    }

    // --- backup / restore ------------------------------------------------------------------------

    @Serializable
    data class Snapshot(
        val version: Int = 1,
        val pendingWords: List<PendingWordRow> = emptyList(),
        val userBigrams: List<UserBigramRow> = emptyList(),
        val blockedWords: List<BlockedWordRow> = emptyList(),
        val autocorrectReverts: List<AutocorrectRevertRow> = emptyList(),
    ) {
        @Serializable data class PendingWordRow(val lang: String, val word: String, val count: Int)
        @Serializable data class UserBigramRow(val lang: String, val w1Norm: String, val w2: String, val count: Int)
        @Serializable data class BlockedWordRow(val lang: String, val word: String)
        @Serializable data class AutocorrectRevertRow(val word: String, val count: Int)
    }

    /** Dumps the entire learning state as a JSON snapshot for the local backup archive. */
    @Synchronized
    fun exportSnapshotJson(): String = runSafely("{}") {
        val db = readableDatabase
        fun <T> rows(sql: String, map: (android.database.Cursor) -> T): List<T> =
            db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
        val snapshot = Snapshot(
            pendingWords = rows("SELECT lang, word, count FROM pending_words") {
                Snapshot.PendingWordRow(it.getString(0), it.getString(1), it.getInt(2))
            },
            userBigrams = rows("SELECT lang, w1_norm, w2, count FROM user_bigrams") {
                Snapshot.UserBigramRow(it.getString(0), it.getString(1), it.getString(2), it.getInt(3))
            },
            blockedWords = rows("SELECT lang, word FROM blocked_words") {
                Snapshot.BlockedWordRow(it.getString(0), it.getString(1))
            },
            autocorrectReverts = rows("SELECT word, count FROM autocorrect_reverts") {
                Snapshot.AutocorrectRevertRow(it.getString(0), it.getInt(1))
            },
        )
        Json.encodeToString(Snapshot.serializer(), snapshot)
    }

    /**
     * Restores a snapshot produced by [exportSnapshotJson]. With [erase] the current state is
     * wiped first; otherwise the snapshot is merged (counters keep the higher value, blocklists
     * are unioned) so restoring an old backup never regresses fresher on-device learning.
     */
    @Synchronized
    fun importSnapshot(json: String, erase: Boolean) = runSafely(Unit) {
        val snapshot = Json { ignoreUnknownKeys = true }.decodeFromString(Snapshot.serializer(), json)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (erase) {
                db.execSQL("DELETE FROM pending_words")
                db.execSQL("DELETE FROM user_bigrams")
                db.execSQL("DELETE FROM blocked_words")
                db.execSQL("DELETE FROM autocorrect_reverts")
            }
            // NOTE: classic insert-then-update instead of UPSERT syntax, which needs SQLite 3.24+
            // (Android 11) while this app supports minSdk 26.
            for (row in snapshot.pendingWords) {
                db.execSQL("INSERT OR IGNORE INTO pending_words (lang, word, count) VALUES (?, ?, 0)", arrayOf(row.lang, row.word))
                db.execSQL(
                    "UPDATE pending_words SET count = MAX(count, ?) WHERE lang = ? AND word = ?",
                    arrayOf<Any>(row.count, row.lang, row.word),
                )
            }
            for (row in snapshot.userBigrams) {
                db.execSQL(
                    "INSERT OR IGNORE INTO user_bigrams (lang, w1_norm, w2, count) VALUES (?, ?, ?, 0)",
                    arrayOf(row.lang, row.w1Norm, row.w2),
                )
                db.execSQL(
                    "UPDATE user_bigrams SET count = MAX(count, ?) WHERE lang = ? AND w1_norm = ? AND w2 = ?",
                    arrayOf<Any>(row.count, row.lang, row.w1Norm, row.w2),
                )
            }
            for (row in snapshot.blockedWords) {
                db.execSQL("INSERT OR IGNORE INTO blocked_words (lang, word) VALUES (?, ?)", arrayOf(row.lang, row.word))
            }
            for (row in snapshot.autocorrectReverts) {
                db.execSQL("INSERT OR IGNORE INTO autocorrect_reverts (word, count) VALUES (?, 0)", arrayOf(row.word))
                db.execSQL(
                    "UPDATE autocorrect_reverts SET count = MAX(count, ?) WHERE word = ?",
                    arrayOf<Any>(row.count, row.word),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Drop in-memory caches so a same-process reader sees the restored state.
        blockedCache.clear()
        autocorrectRevertCache = null
    }

    private inline fun <T> runSafely(fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            flogError { "learning store operation failed: $e" }
            fallback
        }
    }

    companion object {
        const val DB_NAME = "floris_learning"
        private const val DB_VERSION = 1
        private const val PENDING_DECAY_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val BIGRAM_DECAY_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
