/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.smart.config.ui.action.tapelement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import io.github.vibhor1102.macrion.core.domain.model.action.TapElement
import io.github.vibhor1102.macrion.feature.smart.config.domain.EditionRepository

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

import javax.inject.Inject

class TapElementViewModel @Inject constructor(
    private val editionRepository: EditionRepository,
) : ViewModel() {

    private val configuredAction = editionRepository.editionState.editedActionState
        .mapNotNull { action -> action.value }
        .filterIsInstance<TapElement>()

    private val editedActionHasChanged: StateFlow<Boolean> =
        editionRepository.editionState.editedActionState
            .map { it.hasChanged }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Tells if the user is currently editing an action. If that's not the case, dialog should be closed. */
    @OptIn(FlowPreview::class)
    val isEditingAction: Flow<Boolean> = editionRepository.isEditingAction
        .distinctUntilChanged()
        .debounce(1000)

    val uiState: StateFlow<TapElementUiState?> =
        combine(configuredAction, editedActionHasChanged) { action, hasChanged ->
            action.toUiState(hasChanged)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun hasUnsavedModifications(): Boolean = editedActionHasChanged.value

    fun setName(newName: String) = update { copy(name = "" + newName) }

    fun setSelectorType(type: TapElement.SelectorType) = update { copy(selectorType = type) }

    fun setSelectorValue(value: String) = update { copy(selectorValue = "" + value) }

    /** @param seconds the timeout typed by the user, in seconds. Invalid values are kept as an error state. */
    fun setTimeoutSeconds(seconds: String) {
        val millis = seconds.trim().toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L
        update { copy(timeoutMs = millis) }
    }

    private fun update(transform: TapElement.() -> TapElement) {
        editionRepository.editionState.getEditedAction<TapElement>()?.let { action ->
            editionRepository.updateEditedAction(action.transform())
        }
    }

    private fun TapElement.toUiState(hasUnsavedModifications: Boolean): TapElementUiState =
        TapElementUiState(
            canBeSaved = isComplete(),
            hasUnsavedModifications = hasUnsavedModifications,
            name = name ?: "",
            nameError = name.isNullOrEmpty(),
            selectorType = selectorType,
            selectorValue = selectorValue,
            selectorValueError = selectorValue.isBlank(),
            timeoutSeconds = if (timeoutMs < 0) "" else (timeoutMs / 1000.0).let { secs ->
                if (secs % 1.0 == 0.0) secs.toLong().toString() else secs.toString()
            },
            timeoutError = timeoutMs !in 0..TapElement.MAX_TIMEOUT_MS,
        )
}
