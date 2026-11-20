/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.recorder

import android.content.Context
import androidx.core.content.edit

import io.github.vibhor1102.macrion.core.domain.model.event.TriggerEvent

/** Remembers which smart scenarios were made with the tap recorder, to show them as recordings in the list. */
object RecordedScenarios {

    fun add(context: Context, scenarioId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() + scenarioId.toString()
        prefs.edit { putStringSet(KEY_IDS, ids) }
    }

    /**
     * True if the scenario was recorded: saved by this version of the recorder, or made by an older one (a single
     * trigger event with the recorder's name and no screen events).
     */
    fun isRecorded(context: Context, scenarioId: Long, screenEventCount: Int, triggerEvents: List<TriggerEvent>): Boolean {
        val ids = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_IDS, emptySet()).orEmpty()
        }.getOrDefault(emptySet())
        if (scenarioId.toString() in ids) return true
        return screenEventCount == 0 && triggerEvents.size == 1 && triggerEvents.first().name in RECORDED_EVENT_NAMES
    }
}

private const val PREFS_NAME = "recorded_scenarios"
private const val KEY_IDS = "ids"
/** Name of the recorder's event, in every language it was saved with. */
private val RECORDED_EVENT_NAMES = setOf("Recorded taps", "녹화한 탭")
