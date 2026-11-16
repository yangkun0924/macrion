/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

import org.json.JSONArray
import org.json.JSONObject

import java.util.Calendar

/** How a schedule starts its scenario. */
enum class ScheduleMode {
    /** Post a notification at the time, the scenario starts when the user taps "Run". */
    ASK,
    /** Start the scenario directly at the time. */
    AUTO,
}

/**
 * A time of the day, on some days of the week, at which a smart scenario is started (or offered to be started).
 * @param days bitmask of the days, bit 0 is Monday, bit 6 is Sunday.
 */
data class ScenarioSchedule(
    val id: Int,
    val scenarioId: Long,
    val scenarioName: String,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val mode: ScheduleMode,
    val enabled: Boolean = true,
) {
    fun isOnDay(isoDayOfWeek: Int): Boolean = days and (1 shl (isoDayOfWeek - 1)) != 0

    /** Next trigger time after [fromMs], or null if no day is selected. */
    fun nextTriggerMs(fromMs: Long): Long? {
        if (days and ALL_DAYS == 0) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > fromMs && isOnDay(cal.isoDayOfWeek())) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    companion object {
        const val WEEKDAYS: Int = 0b0011111
        const val ALL_DAYS: Int = 0b1111111
    }
}

/** Calendar day to ISO day of week (Monday = 1 ... Sunday = 7). */
internal fun Calendar.isoDayOfWeek(): Int = (get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

/** Stores the schedules and keeps the system alarms in sync with them. */
object ScenarioSchedules {

    fun getAll(context: Context): List<ScenarioSchedule> =
        try {
            val array = JSONArray(prefs(context).getString(KEY_SCHEDULES, "[]"))
            (0 until array.length()).map { array.getJSONObject(it).toSchedule() }
        } catch (ex: Exception) {
            Log.e(TAG, "Can't read schedules", ex)
            emptyList()
        }

    fun getForScenario(context: Context, scenarioId: Long): List<ScenarioSchedule> =
        getAll(context).filter { it.scenarioId == scenarioId }

    fun get(context: Context, id: Int): ScenarioSchedule? = getAll(context).firstOrNull { it.id == id }

    /** Add a new schedule and set its alarm. @return the created schedule. */
    fun add(context: Context, scenarioId: Long, scenarioName: String, hour: Int, minute: Int, days: Int, mode: ScheduleMode): ScenarioSchedule {
        val all = getAll(context)
        val schedule = ScenarioSchedule(
            id = (all.maxOfOrNull { it.id } ?: 0) + 1,
            scenarioId = scenarioId, scenarioName = scenarioName,
            hour = hour, minute = minute, days = days, mode = mode,
        )
        save(context, all + schedule)
        setAlarm(context, schedule)
        return schedule
    }

    fun remove(context: Context, id: Int) {
        cancelAlarm(context, id)
        save(context, getAll(context).filterNot { it.id == id })
    }

    /** Remove all schedules of a deleted scenario. */
    fun removeForScenario(context: Context, scenarioId: Long) {
        getForScenario(context, scenarioId).forEach { remove(context, it.id) }
    }

    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        val updated = getAll(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(context, updated)
        updated.firstOrNull { it.id == id }?.let { if (enabled) setAlarm(context, it) else cancelAlarm(context, id) }
    }

    /** Set again the alarms of all enabled schedules, after a reboot or an app update. */
    fun rescheduleAll(context: Context) {
        getAll(context).forEach { if (it.enabled) setAlarm(context, it) else cancelAlarm(context, it.id) }
    }

    /** True if alarms can be exact (Android 12+ needs the "Alarms & reminders" permission). */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    internal fun setAlarm(context: Context, schedule: ScenarioSchedule) {
        if (!schedule.enabled) return
        val triggerAt = schedule.nextTriggerMs(System.currentTimeMillis()) ?: return cancelAlarm(context, schedule.id)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmIntent(context, schedule.id)

        try {
            if (canScheduleExact(context)) {
                // Shown as an alarm clock: exact even in doze, which is what "every day at 8:30" means for the user.
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
            Log.i(TAG, "Schedule ${schedule.id} set for $triggerAt (exact=${canScheduleExact(context)})")
        } catch (ex: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using an inexact one", ex)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun cancelAlarm(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context, id))
    }

    private fun alarmIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_FIRE)
                .putExtra(ScheduleReceiver.EXTRA_SCHEDULE_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun save(context: Context, schedules: List<ScenarioSchedule>) {
        val array = JSONArray()
        schedules.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_SCHEDULES, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ScenarioSchedule.toJson(): JSONObject = JSONObject()
        .put("id", id).put("scenarioId", scenarioId).put("scenarioName", scenarioName)
        .put("hour", hour).put("minute", minute).put("days", days).put("mode", mode.name).put("enabled", enabled)

    private fun JSONObject.toSchedule(): ScenarioSchedule = ScenarioSchedule(
        id = getInt("id"), scenarioId = getLong("scenarioId"), scenarioName = optString("scenarioName"),
        hour = getInt("hour"), minute = getInt("minute"), days = getInt("days"),
        mode = runCatching { ScheduleMode.valueOf(getString("mode")) }.getOrDefault(ScheduleMode.ASK),
        enabled = optBoolean("enabled", true),
    )
}

private const val TAG = "ScenarioSchedules"
private const val PREFS_NAME = "scenario_schedules"
private const val KEY_SCHEDULES = "schedules"
