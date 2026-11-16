/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.smart.config.ui.action.tapelement

import io.github.vibhor1102.macrion.core.ui.compose.OverlayDialogShape

import android.app.Dialog
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import io.github.vibhor1102.macrion.core.common.overlays.base.viewModels
import io.github.vibhor1102.macrion.core.common.overlays.dialog.OverlayDialog
import io.github.vibhor1102.macrion.core.common.tutorial.domain.model.monitoring.MonitoredOverlayType
import io.github.vibhor1102.macrion.core.domain.model.action.TapElement
import io.github.vibhor1102.macrion.core.ui.compose.MacrionTextField
import io.github.vibhor1102.macrion.core.ui.compose.MacrionTheme
import io.github.vibhor1102.macrion.core.ui.compose.macrionDoneKeyboardActions
import io.github.vibhor1102.macrion.core.ui.compose.macrionDoneKeyboardOptions
import io.github.vibhor1102.macrion.feature.smart.config.R
import io.github.vibhor1102.macrion.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import io.github.vibhor1102.macrion.feature.smart.config.ui.action.OnActionConfigCompleteListener
import io.github.vibhor1102.macrion.feature.smart.config.ui.common.dialogs.showCloseWithoutSavingDialog

import kotlinx.coroutines.launch

/** Configuration dialog for a [TapElement] action. */
class TapElementDialog(
    private val listener: OnActionConfigCompleteListener,
) : OverlayDialog(R.style.ScenarioConfigTheme) {

    override fun tutorialMonitoringTag(): String = MonitoredOverlayType.TAP_ELEMENT.name

    private val viewModel: TapElementViewModel by viewModels(
        entryPoint = ScenarioConfigViewModelsEntryPoint::class.java,
        creator = { tapElementViewModel() },
    )

    override fun onCreateView(): ViewGroup = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MacrionTheme { this@TapElementDialog.Content() } }
    }

    override fun onDialogCreated(dialog: Dialog) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.isEditingAction.collect(::onActionEditingStateChanged)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Content() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val ui = state ?: return
        var timeoutText by remember { mutableStateOf(ui.timeoutSeconds) }

        Surface(
            shape = OverlayDialogShape,
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.fillMaxWidth()) {
                TopBar(ui.canBeSaved)
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MacrionTextField(
                        value = ui.name,
                        onValueChange = viewModel::setName,
                        label = context.getString(R.string.generic_name),
                        isError = ui.nameError,
                        maxLength = context.resources.getInteger(R.integer.name_max_length),
                    )

                    Text(
                        text = context.getString(R.string.field_tap_element_selector_type),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TapElement.SelectorType.entries.forEach { type ->
                            FilterChip(
                                selected = ui.selectorType == type,
                                onClick = { viewModel.setSelectorType(type) },
                                label = { Text(context.getString(type.labelRes())) },
                            )
                        }
                    }

                    MacrionTextField(
                        value = ui.selectorValue,
                        onValueChange = viewModel::setSelectorValue,
                        label = context.getString(ui.selectorType.valueLabelRes()),
                        isError = ui.selectorValueError,
                        maxLength = SELECTOR_MAX_LENGTH,
                    )

                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { value ->
                            timeoutText = value.take(TIMEOUT_MAX_LENGTH)
                            viewModel.setTimeoutSeconds(timeoutText)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(context.getString(R.string.field_tap_element_timeout)) },
                        supportingText = { Text(context.getString(R.string.field_tap_element_timeout_desc)) },
                        isError = ui.timeoutError,
                        singleLine = true,
                        keyboardOptions = macrionDoneKeyboardOptions(KeyboardType.Decimal),
                        keyboardActions = macrionDoneKeyboardActions(),
                    )

                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            text = context.getString(R.string.message_tap_element_help),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    @Composable
    private fun TopBar(saveEnabled: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::back) { Icon(painterResource(R.drawable.ic_cancel), null) }
            Text(
                context.getString(R.string.dialog_title_tap_element_action),
                Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            FilledTonalIconButton(onClick = ::onDeleteButtonClicked) {
                Icon(painterResource(R.drawable.ic_delete), null)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = ::onSaveButtonClicked, enabled = saveEnabled) {
                Icon(painterResource(R.drawable.ic_save_filled), null)
            }
        }
    }

    override fun back() {
        if (viewModel.hasUnsavedModifications()) {
            context.showCloseWithoutSavingDialog {
                listener.onDismissClicked()
                super.back()
            }
            return
        }
        listener.onDismissClicked()
        super.back()
    }

    private fun onSaveButtonClicked() {
        listener.onConfirmClicked()
        super.back()
    }

    private fun onDeleteButtonClicked() {
        listener.onDeleteClicked()
        super.back()
    }

    private fun onActionEditingStateChanged(isEditingAction: Boolean) {
        if (!isEditingAction) {
            Log.e(TAG, "Closing TapElementDialog because there is no action edited")
            finish()
        }
    }
}

private fun TapElement.SelectorType.labelRes(): Int = when (this) {
    TapElement.SelectorType.TEXT_EXACT -> R.string.item_tap_element_selector_text_exact
    TapElement.SelectorType.TEXT_CONTAINS -> R.string.item_tap_element_selector_text_contains
    TapElement.SelectorType.RESOURCE_ID -> R.string.item_tap_element_selector_resource_id
    TapElement.SelectorType.CONTENT_DESCRIPTION -> R.string.item_tap_element_selector_content_desc
}

private fun TapElement.SelectorType.valueLabelRes(): Int = when (this) {
    TapElement.SelectorType.TEXT_EXACT,
    TapElement.SelectorType.TEXT_CONTAINS -> R.string.field_tap_element_value_text
    TapElement.SelectorType.RESOURCE_ID -> R.string.field_tap_element_value_resource_id
    TapElement.SelectorType.CONTENT_DESCRIPTION -> R.string.field_tap_element_value_content_desc
}

private const val SELECTOR_MAX_LENGTH = 200
private const val TIMEOUT_MAX_LENGTH = 5
private const val TAG = "TapElementDialog"
