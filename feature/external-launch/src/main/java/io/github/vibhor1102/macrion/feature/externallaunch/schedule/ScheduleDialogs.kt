/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.schedule

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast

import io.github.vibhor1102.macrion.feature.externallaunch.R

import java.util.Calendar

/** Plain dialogs to list, add and remove the schedules of a scenario. */
object ScheduleDialogs {

    fun show(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean = false) {
        val schedules = ScenarioSchedules.getForScenario(context, scenarioId, isDumb)
        val builder = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_title_schedule, scenarioName))
            .setPositiveButton(R.string.button_schedule_add) { _, _ -> pickTime(context, scenarioId, scenarioName, isDumb) }
            .setNegativeButton(R.string.button_schedule_close, null)

        if (schedules.isEmpty()) {
            builder.setMessage(R.string.message_schedule_empty)
        } else {
            builder.setItems(schedules.map { describe(context, it) }.toTypedArray()) { _, index ->
                editSchedule(context, schedules[index], scenarioId, scenarioName, isDumb)
            }
        }
        builder.show()
    }

    fun describe(context: Context, schedule: ScenarioSchedule): String {
        val time = String.format("%02d:%02d", schedule.hour, schedule.minute)
        val mode = context.getString(
            if (schedule.mode == ScheduleMode.ASK) R.string.item_schedule_mode_ask_short else R.string.item_schedule_mode_auto_short
        )
        val state = if (schedule.enabled) "" else " · " + context.getString(R.string.item_schedule_paused)
        return "${describeDays(context, schedule.days)} $time · $mode$state"
    }

    private fun describeDays(context: Context, days: Int): String {
        val names = context.resources.getStringArray(R.array.schedule_day_names)
        return when (days) {
            ScenarioSchedule.ALL_DAYS -> context.getString(R.string.item_schedule_every_day)
            ScenarioSchedule.WEEKDAYS -> context.getString(R.string.item_schedule_weekdays)
            else -> names.indices.filter { days and (1 shl it) != 0 }.joinToString(",") { names[it] }
        }
    }

    private fun editSchedule(context: Context, schedule: ScenarioSchedule, scenarioId: Long, scenarioName: String, isDumb: Boolean) {
        AlertDialog.Builder(context)
            .setTitle(describe(context, schedule))
            .setItems(
                arrayOf(
                    context.getString(if (schedule.enabled) R.string.button_schedule_pause else R.string.button_schedule_resume),
                    context.getString(R.string.button_schedule_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> ScenarioSchedules.setEnabled(context, schedule.id, !schedule.enabled)
                    1 -> ScenarioSchedules.remove(context, schedule.id)
                }
                show(context, scenarioId, scenarioName, isDumb)
            }
            .setNegativeButton(R.string.button_schedule_close, null)
            .show()
    }

    private fun pickTime(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean) {
        val now = Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, hour, minute -> pickDays(context, scenarioId, scenarioName, isDumb, hour, minute) },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(context),
        ).apply { setTitle(R.string.dialog_title_schedule_time) }.show()
    }

    private fun pickDays(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, hour: Int, minute: Int) {
        val names = context.resources.getStringArray(R.array.schedule_day_names)
        val checked = BooleanArray(7) { it < 5 } // Monday to Friday by default
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_schedule_days)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.button_schedule_next) { _, _ ->
                val days = checked.indices.filter { checked[it] }.fold(0) { acc, i -> acc or (1 shl i) }
                if (days == 0) {
                    Toast.makeText(context, R.string.toast_schedule_no_day, Toast.LENGTH_SHORT).show()
                    pickDays(context, scenarioId, scenarioName, isDumb, hour, minute)
                } else {
                    pickMode(context, scenarioId, scenarioName, isDumb, hour, minute, days)
                }
            }
            .setNegativeButton(R.string.button_schedule_close, null)
            .show()
    }

    private fun pickMode(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, hour: Int, minute: Int, days: Int) {
        var mode = ScheduleMode.ASK
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_schedule_mode)
            .setSingleChoiceItems(
                arrayOf(context.getString(R.string.item_schedule_mode_ask), context.getString(R.string.item_schedule_mode_auto)), 0,
            ) { _, which -> mode = if (which == 0) ScheduleMode.ASK else ScheduleMode.AUTO }
            .setPositiveButton(R.string.button_schedule_save) { _, _ ->
                val schedule = ScenarioSchedules.add(context, scenarioId, scenarioName, hour, minute, days, mode, isDumb)
                Toast.makeText(
                    context, context.getString(R.string.toast_schedule_saved, describe(context, schedule)), Toast.LENGTH_LONG,
                ).show()
                if (!ScenarioSchedules.canScheduleExact(context)) askExactAlarmPermission(context)
            }
            .setNegativeButton(R.string.button_schedule_close, null)
            .show()
    }

    private fun askExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_schedule_exact)
            .setMessage(R.string.message_schedule_exact)
            .setPositiveButton(R.string.button_schedule_open_settings) { _, _ ->
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: ActivityNotFoundException) { }
            }
            .setNegativeButton(R.string.button_schedule_later, null)
            .show()
    }
}
