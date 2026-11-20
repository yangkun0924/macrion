/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.shortcut

import io.github.vibhor1102.macrion.feature.externallaunch.repeat.ScenarioRepeatRunner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

import io.github.vibhor1102.macrion.core.common.permissions.ui.PermissionsHost
import io.github.vibhor1102.macrion.core.display.recorder.MediaProjectionRequest
import io.github.vibhor1102.macrion.core.domain.model.scenario.Scenario
import io.github.vibhor1102.macrion.core.ui.compose.MacrionTheme
import io.github.vibhor1102.macrion.core.ui.errors.createNoMediaProjectionDialog
import io.github.vibhor1102.macrion.feature.externallaunch.R

import dagger.hilt.android.AndroidEntryPoint

/**
 * Started by a home screen shortcut: starts a smart scenario and runs it immediately.
 *
 * Scenarios without screen events (only triggers, with app launch, element taps...) start without screen capture,
 * so the whole flow is a single tap. Others go through the usual screen capture consent.
 */
@AndroidEntryPoint
class ShortcutLauncherActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SCENARIO_ID =
            "io.github.vibhor1102.macrion.feature.externallaunch.shortcut.EXTRA_SCENARIO_ID"

        private const val EXTRA_IS_DUMB =
            "io.github.vibhor1102.macrion.feature.externallaunch.shortcut.EXTRA_IS_DUMB"

        private const val EXTRA_REPEAT_TIMES =
            "io.github.vibhor1102.macrion.feature.externallaunch.shortcut.EXTRA_REPEAT_TIMES"

        private const val EXTRA_SCENARIO_NAME =
            "io.github.vibhor1102.macrion.feature.externallaunch.shortcut.EXTRA_SCENARIO_NAME"

        fun getLaunchIntent(context: Context, scenarioId: Long, isDumb: Boolean = false): Intent =
            Intent(context, ShortcutLauncherActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_SCENARIO_ID, scenarioId)
                .putExtra(EXTRA_IS_DUMB, isDumb)

        /** Intent repeating the scenario [times] times ([ScenarioRepeatRunner.INFINITE]: until stopped). */
        fun getRepeatIntent(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, times: Int): Intent =
            getLaunchIntent(context, scenarioId, isDumb)
                .putExtra(EXTRA_REPEAT_TIMES, times)
                .putExtra(EXTRA_SCENARIO_NAME, scenarioName)
    }

    private val viewModel: ShortcutLauncherViewModel by viewModels()
    private val mediaProjectionRequest: MediaProjectionRequest = MediaProjectionRequest()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionRequest.registerForActivityResult(this)
        setContent { MacrionTheme { PermissionsHost(viewModel.permissionController) } }

        val scenarioId = intent?.getLongExtra(EXTRA_SCENARIO_ID, -1L) ?: -1L
        if (scenarioId == -1L) {
            Log.e(TAG, "Invalid shortcut, no scenario id")
            finish()
            return
        }
        // Don't restart the flow on a configuration change, the permission/projection results will resume it.
        if (savedInstanceState != null) return

        // A repeat shortcut: the runner starts each run through this activity again, without the repeat extra.
        if (intent?.hasExtra(EXTRA_REPEAT_TIMES) == true) {
            ScenarioRepeatRunner.start(
                context = this,
                scenarioId = scenarioId,
                scenarioName = intent?.getStringExtra(EXTRA_SCENARIO_NAME).orEmpty(),
                isDumb = intent?.getBooleanExtra(EXTRA_IS_DUMB, false) == true,
                times = intent?.getIntExtra(EXTRA_REPEAT_TIMES, ScenarioRepeatRunner.INFINITE) ?: ScenarioRepeatRunner.INFINITE,
            )
            finish()
            return
        }

        viewModel.startPermissionFlowIfNeeded(
            activity = this,
            resumeExisting = false,
            onMandatoryDenied = ::finish,
            onAllGranted = {
                if (intent?.getBooleanExtra(EXTRA_IS_DUMB, false) == true) onDumbPermissionsGranted(scenarioId)
                else onPermissionsGranted(scenarioId)
            },
        )
    }

    private fun onDumbPermissionsGranted(scenarioId: Long) {
        viewModel.launchDumbScenario(scenarioId) { found ->
            if (!found) Toast.makeText(this, R.string.toast_shortcut_scenario_not_found, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun onPermissionsGranted(scenarioId: Long) {
        viewModel.loadScenario(scenarioId) { scenario, needsScreenCapture ->
            when {
                scenario == null -> {
                    Toast.makeText(this, R.string.toast_shortcut_scenario_not_found, Toast.LENGTH_LONG).show()
                    finish()
                }
                !needsScreenCapture -> {
                    Log.i(TAG, "Starting scenario ${scenario.id} without screen capture")
                    viewModel.launchWithoutScreenCapture(scenario)
                    finish()
                }
                else -> requestScreenCapture(scenario)
            }
        }
    }

    private fun requestScreenCapture(scenario: Scenario) {
        mediaProjectionRequest.showMediaProjectionWarning(
            context = this,
            forceEntireScreen = viewModel.isEntireScreenCaptureForced(),
            onSuccess = { resultCode, data ->
                viewModel.launchWithScreenCapture(resultCode, data, scenario)
                finish()
            },
            onFailure = {
                Toast.makeText(this, R.string.toast_denied_screen_sharing_permission, Toast.LENGTH_SHORT).show()
                finish()
            },
            onError = { createNoMediaProjectionDialog { finish() }.show() },
        )
    }
}

private const val TAG = "ShortcutLauncherActivity"
