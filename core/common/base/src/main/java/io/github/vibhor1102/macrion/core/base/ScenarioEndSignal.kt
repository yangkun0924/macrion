/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.core.base

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the scenarios that ended on their own (all steps done, repeat count reached...), as opposed to being stopped
 * by the user. The repeat runner uses it to tell a finished run, to run again, from a stop asked by the user.
 */
object ScenarioEndSignal {

    private val finishedCount = AtomicLong(0)

    /** Number of scenario runs that ended by themselves since the app started. */
    val count: Long get() = finishedCount.get()

    /** Called by the engines when a scenario reaches its end without being stopped by the user. */
    fun onFinishedByItself() {
        finishedCount.incrementAndGet()
    }
}
