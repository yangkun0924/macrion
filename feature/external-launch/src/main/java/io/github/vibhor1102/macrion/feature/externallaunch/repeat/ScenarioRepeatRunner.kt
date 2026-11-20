/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.repeat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

import io.github.vibhor1102.macrion.core.base.ScenarioEndSignal
import io.github.vibhor1102.macrion.feature.externallaunch.R
import io.github.vibhor1102.macrion.feature.externallaunch.domain.ExternalLaunchRepository
import io.github.vibhor1102.macrion.feature.externallaunch.shortcut.ShortcutLauncherActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a scenario several times in a row (or until stopped): starts it like a home screen shortcut, waits for it to
 * end by itself, then starts it again. A scenario stopped by the user (stop button, notification) ends the repeat.
 * An ongoing notification shows the run number and a stop button.
 */
object ScenarioRepeatRunner {

    /** Repeat count meaning "until stopped". */
    const val INFINITE = 0

    data class Progress(val scenarioId: Long, val isDumb: Boolean, val run: Int, val times: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    /**
     * Start repeating the scenario.
     * @param times the number of runs, or [INFINITE].
     */
    fun start(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, times: Int) {
        val appContext = context.applicationContext
        val repository = repository(appContext)
        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "Repeat scenario $scenarioId (dumb=$isDumb) ${if (times == INFINITE) "until stopped" else "$times times"}")
            var run = 0
            var reason = R.string.toast_repeat_done
            try {
                while (times == INFINITE || run < times) {
                    run++
                    _progress.value = Progress(scenarioId, isDumb, run, times)
                    showNotification(appContext, scenarioName, run, times)

                    val endedBefore = ScenarioEndSignal.count
                    appContext.startActivity(ShortcutLauncherActivity.getLaunchIntent(appContext, scenarioId, isDumb))

                    // The first run may wait for permissions or the screen capture consent.
                    val started = withTimeoutOrNull(if (run == 1) FIRST_START_TIMEOUT_MS else START_TIMEOUT_MS) {
                        while (!repository.isScenarioRunning()) delay(POLL_MS)
                        true
                    } ?: false
                    if (!started) {
                        Log.w(TAG, "Run $run did not start, stop repeating")
                        reason = R.string.toast_repeat_not_started
                        break
                    }

                    while (repository.isScenarioRunning()) delay(POLL_MS)
                    if (ScenarioEndSignal.count == endedBefore) {
                        Log.i(TAG, "Run $run stopped by the user, stop repeating")
                        reason = R.string.toast_repeat_stopped
                        break
                    }
                    Log.i(TAG, "Run $run done")
                    if (times == INFINITE || run < times) delay(PAUSE_BETWEEN_RUNS_MS)
                }
            } finally {
                _progress.value = null
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, appContext.getString(reason, run), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Stop repeating, and stop the running scenario. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        job?.cancel()
        job = null
        _progress.value = null
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        repository(appContext).stopScenarios()
        Toast.makeText(appContext, R.string.toast_repeat_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun showNotification(context: Context, scenarioName: String, run: Int, times: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_repeat), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID,
            Intent(context, RepeatStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text =
            if (times == INFINITE) context.getString(R.string.notification_repeat_text_infinite, run)
            else context.getString(R.string.notification_repeat_text, run, times)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_repeat_run)
            .setContentTitle(context.getString(R.string.notification_repeat_title, scenarioName))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.notification_repeat_stop), stop)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (ex: SecurityException) {
            Log.w(TAG, "Notification permission denied, no stop button while repeating", ex)
        }
    }

    private fun repository(context: Context): ExternalLaunchRepository =
        EntryPointAccessors.fromApplication(context, RepeatEntryPoint::class.java).externalLaunchRepository()

    internal const val ACTION_STOP = "io.github.vibhor1102.macrion.repeat.STOP"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RepeatEntryPoint {
    fun externalLaunchRepository(): ExternalLaunchRepository
}

/** The "Stop" button of the repeat notification. */
class RepeatStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ScenarioRepeatRunner.ACTION_STOP) ScenarioRepeatRunner.stop(context)
    }
}

private const val TAG = "ScenarioRepeatRunner"
private const val CHANNEL_ID = "scenario_repeat"
private const val NOTIFICATION_ID = 0x5250
private const val POLL_MS = 300L
private const val FIRST_START_TIMEOUT_MS = 90_000L
private const val START_TIMEOUT_MS = 30_000L
private const val PAUSE_BETWEEN_RUNS_MS = 1_500L
