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

package dev.patrickgold.florisboard.app.aman

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import kotlinx.coroutines.flow.first
import org.florisboard.lib.android.AndroidVersion

/**
 * Aman family standards §5 (the dignity rules are binding): the quiet donation reminder engine.
 *
 * - One reminder every 30 days, the first 30 days after first use. Evaluated opportunistically
 *   on keyboard/app usage — no alarms, no background work. Never on first launch.
 * - Low-importance notification channel «دعم أمان» — no sound.
 * - «لاحقًا» (like a swipe) only dismisses the current notification; the monthly cycle
 *   continues. Muting the channel remains possible from the Android system notification
 *   settings, as for any channel — by platform design, not through an in-app switch.
 * - Purely local: no network access, no new permissions (POST_NOTIFICATIONS already exists and
 *   is requested by the setup wizard; without it nothing is ever posted).
 */
object AmanSupportReminder {
    /** The Aman Store package the support screen opens (explicit intent, PackageManager-resolved). */
    const val AMAN_STORE_PACKAGE = "org.amanlabs.store"

    internal const val NOTIFICATION_ID = 0xA3A
    internal const val ACTION_LATER = "org.amanlabs.kalamboard.aman.REMINDER_LATER"

    private const val CHANNEL_ID = "aman_support_reminder"
    private const val REMINDER_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

    private val prefs by FlorisPreferenceStore

    /**
     * Records the first-use timestamp (once) and evaluates whether the monthly support reminder
     * is due. Call from a lifecycle coroutine scope on keyboard/app usage.
     */
    suspend fun onAppActive(context: Context) {
        awaitPreferenceStoreLoaded(context)
        val aman = prefs.amanSupport
        if (aman.firstUseTimestamp.get() == 0L) {
            // First use anchors the cycle: the first reminder follows 30 days from here.
            aman.firstUseTimestamp.set(System.currentTimeMillis())
            return
        }
        maybeShowReminder(context)
    }

    /** Opening the support screen counts as responding to the reminder: the notification is cleared. */
    fun onSupportScreenOpened(context: Context) {
        notificationManager(context)?.cancel(NOTIFICATION_ID)
    }

    private suspend fun awaitPreferenceStoreLoaded(context: Context) {
        val appContext by context.appContext()
        appContext.preferenceStoreLoaded.first { it }
    }

    private suspend fun maybeShowReminder(context: Context) {
        val aman = prefs.amanSupport
        val now = System.currentTimeMillis()
        val last = aman.lastReminderTimestamp.get()
        // The cycle is anchored to the last reminder — or to first use before any was shown.
        val anchor = if (last != 0L) last else aman.firstUseTimestamp.get()
        if (now - anchor < REMINDER_INTERVAL_MS) return
        if (!canPostNotifications(context)) return
        if (showNotification(context)) {
            aman.lastReminderTimestamp.set(now)
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (AndroidVersion.ATLEAST_API33_T) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    internal fun notificationManager(context: Context): NotificationManager? {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    private fun showNotification(context: Context): Boolean {
        val notificationManager = notificationManager(context) ?: return false
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.aman__reminder_channel_name),
            NotificationManager.IMPORTANCE_LOW, // quiet by design: no sound, no heads-up
        )
        notificationManager.createNotificationChannel(channel)

        // Opens the «ادعم أمان» screen via the existing in-app deep link mechanism
        // (ACTION_VIEW + BROWSABLE is what FlorisAppActivity hands to navController.handleDeepLink;
        // the intent stays explicit, so the removed external deep link filter is not needed).
        val supportIntent = Intent(context, FlorisAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addCategory(Intent.CATEGORY_BROWSABLE)
            data = Uri.parse("ui://florisboard/settings/about/aman-support")
        }
        val supportPendingIntent = PendingIntent.getActivity(
            context, 0, supportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val laterPendingIntent = PendingIntent.getBroadcast(
            context, 1, receiverIntent(context, ACTION_LATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = context.getString(R.string.aman__support_primary)
        val actionIcon = Icon.createWithResource(context, R.drawable.ic_app_icon_monochrome)
        val notification = Notification.Builder(context.applicationContext, CHANNEL_ID).run {
            setContentTitle(context.getString(R.string.aman__support_title))
            setContentText(body)
            style = Notification.BigTextStyle().bigText(body)
            setSmallIcon(R.drawable.ic_app_icon_monochrome)
            setContentIntent(supportPendingIntent)
            setAutoCancel(true)
            addAction(
                Notification.Action.Builder(
                    actionIcon,
                    context.getString(R.string.aman__reminder_action_support),
                    supportPendingIntent,
                ).build()
            )
            addAction(
                Notification.Action.Builder(
                    actionIcon,
                    context.getString(R.string.aman__reminder_action_later),
                    laterPendingIntent,
                ).build()
            )
            build()
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun receiverIntent(context: Context, action: String): Intent {
        return Intent(context, AmanSupportReminderReceiver::class.java).setAction(action)
    }
}

/**
 * Handles the «لاحقًا» action of the support reminder notification: it only removes the current
 * notification — the monthly cycle continues. Local only — registered non-exported in the manifest.
 */
class AmanSupportReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AmanSupportReminder.ACTION_LATER) {
            AmanSupportReminder.notificationManager(context)
                ?.cancel(AmanSupportReminder.NOTIFICATION_ID)
        }
    }
}
