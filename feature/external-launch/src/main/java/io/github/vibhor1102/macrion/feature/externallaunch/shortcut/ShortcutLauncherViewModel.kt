/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.shortcut

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import io.github.vibhor1102.macrion.core.base.capture.ScreenCaptureContract
import io.github.vibhor1102.macrion.core.base.data.AppComponentsProvider
import io.github.vibhor1102.macrion.core.base.di.Dispatcher
import io.github.vibhor1102.macrion.core.base.di.HiltCoroutineDispatchers.IO
import io.github.vibhor1102.macrion.core.common.permissions.PermissionsController
import io.github.vibhor1102.macrion.core.common.permissions.model.PermissionAccessibilityService
import io.github.vibhor1102.macrion.core.common.permissions.model.PermissionOverlay
import io.github.vibhor1102.macrion.core.common.permissions.model.PermissionPostNotification
import io.github.vibhor1102.macrion.core.domain.IRepository
import io.github.vibhor1102.macrion.core.domain.model.scenario.Scenario
import io.github.vibhor1102.macrion.core.settings.domain.SettingsRepository
import io.github.vibhor1102.macrion.feature.externallaunch.domain.ExternalLaunchRepository

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ShortcutLauncherViewModel @Inject constructor(
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    private val externalLaunchRepository: ExternalLaunchRepository,
    val permissionController: PermissionsController,
    private val smartRepository: IRepository,
    private val settingsRepository: SettingsRepository,
    private val appComponentsProvider: AppComponentsProvider,
) : ViewModel() {

    fun startPermissionFlowIfNeeded(activity: Context, onAllGranted: () -> Unit, onMandatoryDenied: () -> Unit, resumeExisting: Boolean) {
        permissionController.startPermissionsUiFlow(
            activity = activity,
            permissions = listOf(
                PermissionOverlay(),
                PermissionAccessibilityService(
                    componentName = appComponentsProvider.macrionServiceComponentName,
                    isServiceRunning = { externalLaunchRepository.isAccessibilityServiceStarted() },
                ),
                PermissionPostNotification(optional = true),
            ),
            onAllGranted = onAllGranted,
            onMandatoryDenied = onMandatoryDenied,
            resumeExisting = resumeExisting,
        )
    }

    /**
     * Load the scenario and tell if it needs the screen capture.
     * @param onLoaded called on main thread with the scenario (null if it doesn't exist) and the capture requirement.
     */
    fun loadScenario(scenarioId: Long, onLoaded: (scenario: Scenario?, needsScreenCapture: Boolean) -> Unit) {
        viewModelScope.launch {
            val (scenario, needsCapture) = withContext(ioDispatcher) {
                val scenario = smartRepository.getScenario(scenarioId)
                val needsCapture = scenario != null && smartRepository.getScreenEvents(scenarioId).isNotEmpty()
                scenario to needsCapture
            }
            onLoaded(scenario, needsCapture)
        }
    }

    /** Start the scenario right away without screen capture: no system consent dialog. */
    fun launchWithoutScreenCapture(scenario: Scenario) {
        externalLaunchRepository.replaceSmartScenario(
            resultCode = ScreenCaptureContract.RESULT_CODE_NO_SCREEN_CAPTURE,
            data = Intent(),
            scenario = scenario,
            autoStart = true,
        )
    }

    /** Start the scenario with the media projection granted by the user. */
    fun launchWithScreenCapture(resultCode: Int, data: Intent, scenario: Scenario) {
        externalLaunchRepository.replaceSmartScenario(resultCode, data, scenario, autoStart = true)
    }

    fun isEntireScreenCaptureForced(): Boolean = settingsRepository.isEntireScreenCaptureForced()
}
