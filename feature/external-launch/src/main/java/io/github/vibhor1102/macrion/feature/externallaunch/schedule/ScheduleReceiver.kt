/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.schedule

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import io.github.vibhor1102.macrion.feature.externallaunch.R
import io.github.vibhor1102.macrion.feature.externallaunch.shortcut.ShortcutLauncherActivity

/**
 * Handles the schedule alarms: starts the scenario (AUTO) or posts a notification with a "Run" button (ASK), then sets
 * the alarm for the next occurrence. Also sets all alarms again after a reboot or an app update.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, ACTION_EXACT_ALARM_PERMISSION_CHANGED -> ScenarioSchedules.rescheduleAll(context)

            ACTION_FIRE -> {
                val schedule = ScenarioSchedules.get(context, intent.getIntExtra(EXTRA_SCHEDULE_ID, -1)) ?: return
                ScenarioSchedules.setAlarm(context, schedule) // next occurrence
                if (schedule.enabled) fire(context, schedule.scenarioId, schedule.scenarioName, schedule.mode, schedule.isDumb)
            }

            ACTION_SKIP -> NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))

            // Lets the end to end test fire a schedule right away on debug builds.
            ACTION_TEST_FIRE -> if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                val mode = runCatching { ScheduleMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }.getOrDefault(ScheduleMode.ASK)
                fire(
                    context, intent.getLongExtra(EXTRA_SCENARIO_ID, -1L), intent.getStringExtra(EXTRA_SCENARIO_NAME) ?: "", mode,
                    intent.getBooleanExtra(EXTRA_IS_DUMB, false),
                )
            }
        }
    }

    private fun fire(context: Context, scenarioId: Long, scenarioName: String, mode: ScheduleMode, isDumb: Boolean) {
        Log.i(TAG, "Schedule fired for scenario $scenarioId, mode $mode")
        if (scenarioId == -1L) return

        if (mode == ScheduleMode.AUTO) {
            if (isLocked(context)) {
                // A locked phone can't be unlocked by an app: ask the user instead.
                notify(context, scenarioId, scenarioName, isDumb, context.getString(R.string.notification_schedule_text_locked))
                return
            }
            try {
                // Allowed from the background because the app has the "display over other apps" permission.
                context.startActivity(ShortcutLauncherActivity.getLaunchIntent(context, scenarioId, isDumb))
                return
            } catch (ex: Exception) {
                Log.w(TAG, "Can't start the scenario from the background, asking the user", ex)
            }
        }

        notify(context, scenarioId, scenarioName, isDumb, context.getString(R.string.notification_schedule_text_ask))
    }

    private fun notify(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, text: String) {
        createChannel(context)
        val notificationId = NOTIFICATION_ID_BASE + (scenarioId % 10_000).toInt() + if (isDumb) 10_000 else 0
        val run = PendingIntent.getActivity(
            context, notificationId,
            ShortcutLauncherActivity.getLaunchIntent(context, scenarioId, isDumb),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val skip = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_SKIP).putExtra(EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.notification_schedule_title, scenarioName.ifBlank {
            context.getString(R.string.shortcut_default_label)
        })

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_run_scenario)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(run)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .addAction(0, context.getString(R.string.notification_schedule_run), run)
            .addAction(0, context.getString(R.string.notification_schedule_skip), skip)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (ex: SecurityException) {
            Log.w(TAG, "Notification permission denied, can't ask to run the scenario", ex)
        }
    }

    private fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_schedule), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val ACTION_FIRE = "io.github.vibhor1102.macrion.schedule.FIRE"
        const val ACTION_SKIP = "io.github.vibhor1102.macrion.schedule.SKIP"
        const val ACTION_TEST_FIRE = "io.github.vibhor1102.macrion.schedule.TEST_FIRE"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_SCENARIO_ID = "scenarioId"
        const val EXTRA_SCENARIO_NAME = "scenarioName"
        const val EXTRA_MODE = "mode"
        const val EXTRA_IS_DUMB = "dumb"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

private const val TAG = "ScheduleReceiver"
private const val CHANNEL_ID = "scenario_schedule"
private const val NOTIFICATION_ID_BASE = 0x5C00
private const val NOTIFICATION_TIMEOUT_MS = 30 * 60 * 1000L
