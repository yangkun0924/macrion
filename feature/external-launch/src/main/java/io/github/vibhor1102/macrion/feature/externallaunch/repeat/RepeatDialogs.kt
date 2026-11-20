/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.repeat

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

import io.github.vibhor1102.macrion.feature.externallaunch.R
import io.github.vibhor1102.macrion.feature.externallaunch.shortcut.ScenarioShortcuts

/** Plain dialogs to choose how many times to run a scenario, then start now or make a home screen shortcut. */
object RepeatDialogs {

    private val COUNTS = intArrayOf(2, 3, 5, 10, 20)

    fun show(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean = false) {
        val items = COUNTS.map { context.getString(R.string.item_repeat_times, it) } +
            context.getString(R.string.item_repeat_infinite) +
            context.getString(R.string.item_repeat_custom)

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_title_repeat, scenarioName))
            .setItems(items.toTypedArray()) { _, index ->
                when {
                    index < COUNTS.size -> confirm(context, scenarioId, scenarioName, isDumb, COUNTS[index])
                    index == COUNTS.size -> confirm(context, scenarioId, scenarioName, isDumb, ScenarioRepeatRunner.INFINITE)
                    else -> askCount(context, scenarioId, scenarioName, isDumb)
                }
            }
            .setNegativeButton(R.string.button_repeat_cancel, null)
            .show()
    }

    private fun askCount(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.hint_repeat_count)
            setSingleLine()
        }
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_repeat_count)
            .setView(container)
            .setPositiveButton(R.string.button_repeat_next) { _, _ ->
                val count = input.text.toString().trim().toIntOrNull()
                if (count == null || count < 1 || count > MAX_COUNT) {
                    Toast.makeText(context, context.getString(R.string.toast_repeat_invalid_count, MAX_COUNT), Toast.LENGTH_LONG).show()
                } else {
                    confirm(context, scenarioId, scenarioName, isDumb, count)
                }
            }
            .setNegativeButton(R.string.button_repeat_cancel, null)
            .show()
    }

    private fun confirm(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, times: Int) {
        val message =
            if (times == ScenarioRepeatRunner.INFINITE) context.getString(R.string.message_repeat_confirm_infinite, scenarioName)
            else context.getString(R.string.message_repeat_confirm, scenarioName, times)

        AlertDialog.Builder(context)
            .setTitle(ScenarioShortcuts.repeatLabel(context, scenarioName, times))
            .setMessage(message)
            .setPositiveButton(R.string.button_repeat_start) { _, _ ->
                ScenarioRepeatRunner.start(context, scenarioId, scenarioName, isDumb, times)
            }
            .setNeutralButton(R.string.button_repeat_shortcut) { _, _ ->
                ScenarioShortcuts.requestPinSmartScenario(context, scenarioId, scenarioName, isDumb, repeatTimes = times)
            }
            .setNegativeButton(R.string.button_repeat_cancel, null)
            .show()
    }
}

private const val MAX_COUNT = 999
