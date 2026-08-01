/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.kotlin.CurlyArg
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.reflect.KClass

/**
 * OFFLINE BUILD — DOES NOT OPEN ANYTHING. The name is kept unchanged on purpose, so that merges
 * with upstream stay manageable, but the behaviour is deliberately different.
 *
 * Upstream fired an [Intent.ACTION_VIEW] here, handing [url] to whichever external app claims it
 * (normally a browser). That is a hand-off this build must never initiate: it is the single point
 * through which every "visit repo / privacy policy / changelog / maintainer homepage" button, and
 * every link inside an *imported third-party extension manifest*, could reach the network.
 *
 * Instead the URL is copied to the system clipboard and shown to the user, who stays free to open
 * it deliberately in an app of their own choosing. No component is ever started from here.
 */
fun Context.launchUrl(url: String) {
    val clipboardManager = this.systemServiceOrNull(ClipboardManager::class)
    clipboardManager?.setPrimaryClip(ClipData.newPlainText(url, url))
    Toast.makeText(
        this,
        this.stringRes(R.string.general__url_copied_to_clipboard, "url" to url),
        Toast.LENGTH_LONG,
    ).show()
}

fun Context.launchUrl(@StringRes url: Int) {
    launchUrl(this.stringRes(url))
}

fun Context.launchUrl(@StringRes url: Int, vararg args: CurlyArg) {
    launchUrl(this.stringRes(url, *args))
}

inline fun <T : Any> Context.launchActivity(kClass: KClass<T>, intentModifier: (Intent) -> Unit = { }) {
    contract {
        callsInPlace(intentModifier, InvocationKind.AT_MOST_ONCE)
    }
    try {
        val intent = Intent(this, kClass.java)
        intentModifier(intent)
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        flogError { e.toString() }
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }
}

inline fun Context.launchActivity(intentModifier: (Intent) -> Unit) {
    contract {
        callsInPlace(intentModifier, InvocationKind.AT_MOST_ONCE)
    }
    try {
        val intent = Intent()
        intentModifier(intent)
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        flogError { e.toString() }
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }
}
