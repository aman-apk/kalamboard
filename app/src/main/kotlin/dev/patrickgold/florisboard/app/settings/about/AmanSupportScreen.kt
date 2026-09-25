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

package dev.patrickgold.florisboard.app.settings.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.aman.AmanSupportReminder
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.stringRes

/**
 * Aman family standards §5: the quiet «ادعم أمان» donation screen. Shows the non-profit line
 * and a single button that opens the Aman Store app when installed (explicit intent, resolved
 * via PackageManager). The app has no Internet permission and never gets one — the browser
 * fallback only activates once [R.string.aman_website_url] is filled with a real address
 * (it ships as a clearly-marked TODO placeholder).
 */
@Composable
fun AmanSupportScreen() = FlorisScreen {
    title = stringRes(R.string.aman__support_title)

    val context = LocalContext.current

    content {
        // Opening this screen counts as responding to the support reminder: clear the
        // notification (if shown).
        LaunchedEffect(Unit) {
            AmanSupportReminder.onSupportScreenOpened(context)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringRes(R.string.aman__nonprofit_line),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringRes(R.string.aman__support_primary),
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { launchAmanStore(context) }) {
                Text(text = stringRes(R.string.aman__support_open_store))
            }
        }
    }
}

private fun launchAmanStore(context: Context) {
    val storeIntent = context.packageManager
        .getLaunchIntentForPackage(AmanSupportReminder.AMAN_STORE_PACKAGE)
    if (storeIntent != null) {
        try {
            context.startActivity(storeIntent)
            return
        } catch (_: Exception) {
            // Store vanished between resolution and launch — fall through to the fallback below.
        }
    }
    val websiteUrl = context.getString(R.string.aman_website_url)
    if (websiteUrl.contains("TODO")) {
        // Placeholder not filled in yet — never open a browser on a dead link.
        Toast.makeText(context, R.string.aman__support_store_missing, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.aman__support_store_missing, Toast.LENGTH_SHORT).show()
    }
}
