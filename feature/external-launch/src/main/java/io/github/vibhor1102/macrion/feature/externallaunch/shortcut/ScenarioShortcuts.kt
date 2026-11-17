/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.shortcut

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

import io.github.vibhor1102.macrion.feature.externallaunch.R

/** Home screen shortcuts starting a smart scenario with a single tap. */
object ScenarioShortcuts {

    /**
     * Ask the launcher to pin a shortcut starting the given smart scenario.
     * The user confirms the placement in the launcher dialog.
     *
     * A shortcut already on the home screen for this scenario is renamed first: launchers keep the label of the first
     * pin otherwise, the name given to the scenario afterwards would never show.
     */
    fun requestPinSmartScenario(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean = false) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.toast_shortcut_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        val shortcut = buildShortcut(context, scenarioId, scenarioName, isDumb)
        if (isPinned(context, shortcut.id)) {
            update(context, listOf(shortcut))
            Toast.makeText(context, R.string.toast_shortcut_renamed, Toast.LENGTH_LONG).show()
        }
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * Give the shortcuts on the home screen the current names of their scenarios (a scenario renamed in the editor
     * keeps its shortcut). Scenarios are given as (id, name, isDumb).
     */
    fun syncNames(context: Context, scenarios: List<Triple<Long, String, Boolean>>) {
        val pinned = try {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't read the pinned shortcuts", ex)
            return
        }
        if (pinned.isEmpty()) return
        val labels = pinned.associate { it.id to it.shortLabel.toString() }
        val changed = scenarios.mapNotNull { (id, name, isDumb) ->
            val shortcut = buildShortcut(context, id, name, isDumb)
            val current = labels[shortcut.id] ?: return@mapNotNull null
            shortcut.takeIf { current != it.shortLabel.toString() }
        }
        if (changed.isNotEmpty()) update(context, changed)
    }

    private fun buildShortcut(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean): ShortcutInfoCompat {
        val label = scenarioName.trim().ifBlank { context.getString(R.string.shortcut_default_label) }
        return ShortcutInfoCompat.Builder(context, shortcutId(scenarioId, isDumb))
            .setShortLabel(label.take(SHORT_LABEL_MAX_LENGTH))
            .setLongLabel(label.take(LONG_LABEL_MAX_LENGTH))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_run_scenario))
            .setIntent(ShortcutLauncherActivity.getLaunchIntent(context, scenarioId, isDumb))
            .build()
    }

    private fun shortcutId(scenarioId: Long, isDumb: Boolean): String =
        if (isDumb) "dumb-scenario-$scenarioId" else "smart-scenario-$scenarioId"

    private fun isPinned(context: Context, id: String): Boolean = try {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == id }
    } catch (ex: Exception) {
        false
    }

    private fun update(context: Context, shortcuts: List<ShortcutInfoCompat>) {
        try {
            ShortcutManagerCompat.updateShortcuts(context, shortcuts)
            Log.i(TAG, "Renamed shortcuts: ${shortcuts.map { it.id to it.shortLabel }}")
        } catch (ex: Exception) {
            Log.w(TAG, "Can't rename the shortcuts", ex)
        }
    }
}

private const val TAG = "ScenarioShortcuts"
// Launchers cut long names themselves: keep a name readable under the icon rather than a fixed 10 characters.
private const val SHORT_LABEL_MAX_LENGTH = 25
private const val LONG_LABEL_MAX_LENGTH = 100
