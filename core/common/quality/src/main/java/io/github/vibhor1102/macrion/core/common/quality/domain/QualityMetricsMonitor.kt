/*
 * Copyright (C) 2024 Kevin Buzeau
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
package io.github.vibhor1102.macrion.core.common.quality.domain

import android.content.Context
import android.os.SystemClock
import android.util.Log

import dagger.hilt.android.qualifiers.ApplicationContext

import io.github.vibhor1102.macrion.core.base.di.Dispatcher
import io.github.vibhor1102.macrion.core.base.di.HiltCoroutineDispatchers
import io.github.vibhor1102.macrion.core.common.quality.data.INVALID_TIME
import io.github.vibhor1102.macrion.core.common.quality.data.QualityDataSource
import io.github.vibhor1102.macrion.core.common.quality.data.QualityMetrics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QualityMetricsMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qualityDataSource: QualityDataSource,
    @Dispatcher(HiltCoroutineDispatchers.IO) ioDispatcher: CoroutineDispatcher,
) {

    private val coroutineScopeIo: CoroutineScope =
        CoroutineScope(SupervisorJob() + ioDispatcher)

    internal val currentQualityMetrics: Flow<QualityMetrics> = qualityDataSource
        .qualityMetrics()

    internal val startingQualityMetrics: Flow<QualityMetrics> = currentQualityMetrics
        .take(1)
        .shareIn(coroutineScopeIo, SharingStarted.Eagerly, replay = 1)

    fun onServiceConnected() {
        coroutineScopeIo.launch {
            qualityDataSource.edit { currentMetrics ->

                var newLossCount = currentMetrics.accessibilityLossCount
                if (currentMetrics.lastServiceStartTimeMs != INVALID_TIME
                    && currentMetrics.lastScenarioStartTimeMs == INVALID_TIME
                    && !isExpectedRestart(currentMetrics.lastServiceStartTimeMs)) {
                    newLossCount += 1
                }

                currentMetrics.copy(
                    lastServiceStartTimeMs = System.currentTimeMillis(),
                    accessibilityLossCount = newLossCount,
                )
            }
        }
    }

    /**
     * Installing an update of the app or rebooting the device also stops the accessibility service: this is not the
     * device killing the app, and must not show the troubleshooting dialog.
     */
    private fun isExpectedRestart(lastServiceStartTimeMs: Long): Boolean {
        val bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val updateTimeMs = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (ex: Exception) {
            0L
        }
        val expected = bootTimeMs > lastServiceStartTimeMs || updateTimeMs > lastServiceStartTimeMs
        if (expected) Log.i(TAG, "Accessibility service restarted after an app update or a reboot, not counted as a loss")
        return expected
    }

    fun onServiceForegroundStart() {
        coroutineScopeIo.launch {
            qualityDataSource.edit { currentMetrics ->
                currentMetrics.copy(lastScenarioStartTimeMs = System.currentTimeMillis())
            }
        }
    }

    fun onServiceForegroundEnd() {
        coroutineScopeIo.launch {
            qualityDataSource.edit { currentMetrics ->
                currentMetrics.copy(lastScenarioStartTimeMs = INVALID_TIME)
            }
        }
    }

    fun onServiceUnbind() {
        Log.w(TAG, "Accessibility service is unbound. If you haven't touched the accessibility permission, this " +
                " means your Android device manufacturer does not comply with Android standards and have decided to kill" +
                " Macrion."
        )
    }

    internal fun onTroubleshootingDisplayed() {
        coroutineScopeIo.launch {
            qualityDataSource.edit { currentMetrics ->
                currentMetrics.copy(troubleshootingDisplayCount = currentMetrics.troubleshootingDisplayCount + 1)
            }
        }
    }
}

private const val TAG = "QualityMonitor"
