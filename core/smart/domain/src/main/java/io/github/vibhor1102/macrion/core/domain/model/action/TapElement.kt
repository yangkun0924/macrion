/*
 * Copyright (C) 2026 Macrion contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.github.vibhor1102.macrion.core.domain.model.action

import io.github.vibhor1102.macrion.core.base.identifier.Identifier

/**
 * Tap a UI element found in the accessibility tree of the current window, instead of a fixed position.
 *
 * The element is searched until it appears or [timeoutMs] is elapsed. This makes the action resilient to screen
 * size and layout changes, the same way test automation selectors are.
 *
 * @param selectorType how the element is identified.
 * @param selectorValue the text, resource id or content description to look for.
 * @param timeoutMs how long to wait for the element to appear before giving up, in milliseconds.
 */
data class TapElement(
    override val id: Identifier,
    override val eventId: Identifier,
    override val name: String? = null,
    override var priority: Int,
    val selectorType: SelectorType = SelectorType.TEXT_EXACT,
    val selectorValue: String = "",
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : Action() {

    enum class SelectorType {
        TEXT_EXACT,
        TEXT_CONTAINS,
        RESOURCE_ID,
        CONTENT_DESCRIPTION,
    }

    override fun isComplete(): Boolean =
        super.isComplete() && selectorValue.isNotBlank() && timeoutMs in 0..MAX_TIMEOUT_MS

    override fun hashCodeNoIds(): Int =
        name.hashCode() + selectorType.hashCode() + selectorValue.hashCode() + timeoutMs.hashCode()

    override fun deepCopy(): TapElement = copy(name = "" + name)

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
        const val MAX_TIMEOUT_MS: Long = 60_000L
    }
}
