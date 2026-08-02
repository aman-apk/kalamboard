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

package dev.patrickgold.florisboard.app.settings.typing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.nlp.words.LearningStore
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisTextButton
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.curlyFormat
import java.time.LocalDate

private data class TypingStats(
    val totalWords: Long,
    val totalChars: Long,
    val activeDays: Int,
    val streakDays: Int,
    val bestDay: LearningStore.DailyStat?,
    val lastDays: List<LearningStore.DailyStat>,
    val topWords: List<Pair<String, Int>>,
)

/** Consecutive-day streak ending today (or yesterday, so an unfinished day doesn't break it). */
private fun computeStreak(dates: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in dates) today else today.minusDays(1)
    var streak = 0
    while (day in dates) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/**
 * Local, private typing statistics — computed entirely from the on-device `floris_learning`
 * database (`stats_daily` / `stats_words`). Nothing here ever leaves the device; incognito
 * sessions are never counted.
 */
@Composable
fun TypingStatsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__typing_stats__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Short-lived store per query, opened and closed inside the same IO block — no remembered
    // instance whose close() could race an in-flight read on screen exit.
    val stats by produceState<TypingStats?>(initialValue = null, reloadTrigger) {
        value = withContext(Dispatchers.IO) {
            val store = LearningStore(context.applicationContext)
            try {
                val (words, chars, activeDays) = store.statsTotals()
                val daily = store.dailyStats(90)
                val dates = daily.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
                TypingStats(
                    totalWords = words,
                    totalChars = chars,
                    activeDays = activeDays,
                    streakDays = computeStreak(dates, LocalDate.now()),
                    bestDay = daily.maxByOrNull { it.words },
                    lastDays = daily.take(14).reversed(),
                    topWords = store.topWords(12),
                )
            } finally {
                store.close()
            }
        }
    }

    content {
        val data = stats ?: return@content

        FlorisOutlinedBox(modifier = Modifier.defaultFlorisOutlinedBox()) {
            Row(modifier = Modifier.padding(16.dp)) {
                StatCell(
                    value = data.totalWords.toString(),
                    label = stringRes(R.string.settings__typing_stats__total_words),
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    value = data.activeDays.toString(),
                    label = stringRes(R.string.settings__typing_stats__active_days),
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    value = data.streakDays.toString(),
                    label = stringRes(R.string.settings__typing_stats__streak),
                    modifier = Modifier.weight(1f),
                )
            }
            data.bestDay?.let { best ->
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    text = stringRes(R.string.settings__typing_stats__best_day)
                        .curlyFormat("date" to best.date, "count" to best.words.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (data.lastDays.isNotEmpty()) {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = stringRes(R.string.settings__typing_stats__last_days),
            ) {
                val maxWords = data.lastDays.maxOf { it.words }.coerceAtLeast(1)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(96.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (day in data.lastDays) {
                        val fraction = day.words.toFloat() / maxWords
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((6 + 90 * fraction).dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.65f * fraction)),
                        )
                    }
                }
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    text = stringRes(R.string.settings__typing_stats__last_days_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (data.topWords.isNotEmpty()) {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = stringRes(R.string.settings__typing_stats__top_words),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    for ((word, count) in data.topWords) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = word,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (data.totalWords == 0L) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringRes(R.string.settings__typing_stats__empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                FlorisTextButton(
                    onClick = { showClearDialog = true },
                    icon = Icons.Default.DeleteSweep,
                    text = stringRes(R.string.settings__typing_stats__clear),
                )
            }
        }
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            text = stringRes(R.string.settings__typing_stats__privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (showClearDialog) {
            dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog(
                title = stringRes(R.string.settings__typing_stats__clear),
                confirmLabel = stringRes(R.string.action__yes),
                onConfirm = {
                    showClearDialog = false
                    scope.launch(Dispatchers.IO) {
                        val store = LearningStore(context.applicationContext)
                        try {
                            store.clearStats()
                        } finally {
                            store.close()
                        }
                        reloadTrigger++
                    }
                },
                dismissLabel = stringRes(R.string.action__no),
                onDismiss = { showClearDialog = false },
            ) {
                Text(stringRes(R.string.settings__typing_stats__clear_confirm))
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
