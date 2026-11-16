/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.shortcut

import android.content.Context
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
     */
    fun requestPinSmartScenario(context: Context, scenarioId: Long, scenarioName: String) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.toast_shortcut_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        val label = scenarioName.ifBlank { context.getString(R.string.shortcut_default_label) }
        val shortcut = ShortcutInfoCompat.Builder(context, "smart-scenario-$scenarioId")
            .setShortLabel(label.take(SHORT_LABEL_MAX_LENGTH))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_run_scenario))
            .setIntent(ShortcutLauncherActivity.getLaunchIntent(context, scenarioId))
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}

private const val SHORT_LABEL_MAX_LENGTH = 10
