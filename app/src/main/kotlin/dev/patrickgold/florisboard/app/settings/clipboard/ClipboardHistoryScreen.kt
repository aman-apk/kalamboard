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

package dev.patrickgold.florisboard.app.settings.clipboard

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisTextButton
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.curlyFormat
import java.text.DateFormat
import java.util.Date

/**
 * In-app browser for the long-term clipboard history: lists every stored item (pinned first),
 * with search, tap-to-copy, pin/unpin and delete. The IME clipboard panel shows the same data
 * inside the keyboard; this screen is the "browse everything" management surface the panel is
 * too small for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardHistoryScreen() = FlorisScreen {
    title = stringRes(R.string.settings__clipboard_history__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val history by clipboardManager.historyFlow.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var itemForActions by remember { mutableStateOf<ClipboardItem?>(null) }
    var showClearUnpinnedDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun matchesSearch(item: ClipboardItem): Boolean {
        if (searchQuery.isBlank()) return true
        return item.text?.contains(searchQuery, ignoreCase = true) == true
    }

    content {
        // Privacy lock: the full history browser sits behind the device's own unlock screen
        // (fingerprint or PIN/pattern, whatever the device uses). Only enforced when the pref is
        // on AND the device actually has a secure lock configured — otherwise there is nothing
        // to authenticate against and the screen opens directly.
        val lockEnabled by prefs.clipboard.historyScreenLock.collectAsState()
        val keyguardManager = remember {
            context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        val lockRequired = lockEnabled && keyguardManager.isDeviceSecure
        var unlocked by rememberSaveable { mutableStateOf(false) }
        val unlockTitle = stringRes(R.string.settings__clipboard_history__lock_prompt_title)
        val unlockSummary = stringRes(R.string.settings__clipboard_history__lock_prompt_summary)
        val unlockLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) unlocked = true
        }
        fun launchUnlock() {
            @Suppress("DEPRECATION") // Replacement (BiometricPrompt) needs a FragmentActivity + extra dependency.
            val intent = keyguardManager.createConfirmDeviceCredentialIntent(unlockTitle, unlockSummary)
            if (intent != null) unlockLauncher.launch(intent) else unlocked = true
        }
        if (lockRequired && !unlocked) {
            LaunchedEffect(Unit) { launchUnlock() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringRes(R.string.settings__clipboard_history__locked_message),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { launchUnlock() }) {
                    Text(stringRes(R.string.settings__clipboard_history__unlock_action))
                }
            }
            return@content
        }

        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = stringRes(R.string.settings__clipboard_history__summary)
                    .curlyFormat("count" to history.all.size.toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.padding(horizontal = 6.dp)) {
                FlorisTextButton(
                    onClick = { showClearUnpinnedDialog = true },
                    icon = Icons.Default.DeleteSweep,
                    text = stringRes(R.string.settings__clipboard_history__clear_unpinned),
                    enabled = history.unpinned.isNotEmpty(),
                )
                FlorisTextButton(
                    onClick = { showClearAllDialog = true },
                    icon = Icons.Default.Delete,
                    text = stringRes(R.string.settings__clipboard_history__clear_all),
                    enabled = history.all.isNotEmpty(),
                )
            }
        }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            placeholder = { Text(stringRes(R.string.settings__clipboard_history__search_placeholder)) },
        )

        val pinnedItems = history.pinned.filter(::matchesSearch)
        val unpinnedItems = history.unpinned.filter(::matchesSearch)

        if (pinnedItems.isEmpty() && unpinnedItems.isEmpty()) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringRes(R.string.settings__clipboard_history__empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        @Composable
        fun HistoryItemRow(item: ClipboardItem) {
            JetPrefListItem(
                modifier = Modifier.combinedClickable(
                    onClick = {
                        // Tap = copy back to the current clipboard (text items only).
                        if (item.type == ItemType.TEXT && item.text != null) {
                            clipboardManager.addNewPlaintext(item.text)
                            context.showShortToastSync(R.string.settings__clipboard_history__copied_toast)
                        } else {
                            itemForActions = item
                        }
                    },
                    onLongClick = { itemForActions = item },
                ),
                icon = {
                    Icon(
                        imageVector = when {
                            item.isPinned -> Icons.Default.PushPin
                            item.type == ItemType.IMAGE -> Icons.Default.Image
                            item.type == ItemType.VIDEO -> Icons.Default.Videocam
                            else -> Icons.AutoMirrored.Filled.TextSnippet
                        },
                        contentDescription = null,
                    )
                },
                text = when {
                    item.isSensitive -> stringRes(R.string.settings__clipboard_history__sensitive_content)
                    item.type == ItemType.TEXT -> item.text.toString().let {
                        if (it.length > 180) it.take(180) + "…" else it
                    }
                    item.type == ItemType.IMAGE -> stringRes(R.string.settings__clipboard_history__image_item)
                    else -> stringRes(R.string.settings__clipboard_history__video_item)
                },
                secondaryText = dateFormatter.format(Date(item.creationTimestampMs)),
            )
        }

        if (pinnedItems.isNotEmpty()) {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = stringRes(R.string.settings__clipboard_history__section_pinned),
            ) {
                for (item in pinnedItems) {
                    HistoryItemRow(item)
                }
            }
        }
        if (unpinnedItems.isNotEmpty()) {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = stringRes(R.string.settings__clipboard_history__section_history),
            ) {
                for (item in unpinnedItems) {
                    HistoryItemRow(item)
                }
            }
        }

        itemForActions?.let { item ->
            JetPrefAlertDialog(
                title = stringRes(R.string.settings__clipboard_history__item_actions),
                onDismiss = { itemForActions = null },
                dismissLabel = stringRes(R.string.action__cancel),
            ) {
                androidx.compose.foundation.layout.Column {
                    if (item.type == ItemType.TEXT && item.text != null) {
                        FlorisTextButton(
                            onClick = {
                                clipboardManager.addNewPlaintext(item.text)
                                context.showShortToastSync(R.string.settings__clipboard_history__copied_toast)
                                itemForActions = null
                            },
                            text = stringRes(R.string.settings__clipboard_history__action_copy),
                        )
                    }
                    FlorisTextButton(
                        onClick = {
                            if (item.isPinned) clipboardManager.unpinClip(item) else clipboardManager.pinClip(item)
                            itemForActions = null
                        },
                        text = stringRes(
                            if (item.isPinned) R.string.settings__clipboard_history__action_unpin
                            else R.string.settings__clipboard_history__action_pin
                        ),
                    )
                    FlorisTextButton(
                        onClick = {
                            clipboardManager.deleteClip(item, onlyIfUnpinned = false)
                            itemForActions = null
                        },
                        text = stringRes(R.string.settings__clipboard_history__action_delete),
                    )
                }
            }
        }

        if (showClearUnpinnedDialog) {
            JetPrefAlertDialog(
                title = stringRes(R.string.settings__clipboard_history__clear_unpinned),
                confirmLabel = stringRes(R.string.action__yes),
                onConfirm = {
                    clipboardManager.clearHistory()
                    showClearUnpinnedDialog = false
                },
                dismissLabel = stringRes(R.string.action__no),
                onDismiss = { showClearUnpinnedDialog = false },
            ) {
                Text(stringRes(R.string.settings__clipboard_history__clear_unpinned_confirm))
            }
        }
        if (showClearAllDialog) {
            JetPrefAlertDialog(
                title = stringRes(R.string.settings__clipboard_history__clear_all),
                confirmLabel = stringRes(R.string.action__yes),
                onConfirm = {
                    clipboardManager.clearFullHistory()
                    showClearAllDialog = false
                },
                dismissLabel = stringRes(R.string.action__no),
                onDismiss = { showClearAllDialog = false },
            ) {
                Text(stringRes(R.string.settings__clipboard_history__clear_all_confirm))
            }
        }
    }
}
