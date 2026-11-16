/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.smart.config.ui.action.tapelement

import io.github.vibhor1102.macrion.core.domain.model.action.TapElement

data class TapElementUiState(
    val canBeSaved: Boolean,
    val hasUnsavedModifications: Boolean,
    val name: String,
    val nameError: Boolean,
    val selectorType: TapElement.SelectorType,
    val selectorValue: String,
    val selectorValueError: Boolean,
    val timeoutSeconds: String,
    val timeoutError: Boolean,
)
