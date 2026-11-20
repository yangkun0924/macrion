/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.recorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import io.github.vibhor1102.macrion.R
import io.github.vibhor1102.macrion.feature.externallaunch.shortcut.ScenarioShortcuts
import io.github.vibhor1102.macrion.scenarios.ScenarioActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts and stops the tap recording, with an ongoing notification holding the stop and cancel buttons. */
object RecorderController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start recording and send the user to the home screen, so they can open the app to record.
     * @return false if the accessibility service is not enabled.
     */
    fun startRecording(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!TapRecorder.start(appContext)) {
            Toast.makeText(appContext, R.string.toast_recorder_service_disabled, Toast.LENGTH_LONG).show()
            return false
        }

        showNotification(appContext)
        Toast.makeText(appContext, R.string.toast_recorder_started, Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /**
     * Stop the recording, ask the user to name it, save it, then show the scenario list and offer the home screen
     * shortcut.
     */
    internal fun stopRecording(activity: Activity) {
        val appContext = activity.applicationContext
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)

        val recording = TapRecorder.stop()
        if (recording == null || recording.isEmpty) {
            Toast.makeText(appContext, R.string.toast_recorder_nothing_recorded, Toast.LENGTH_LONG).show()
            activity.finish()
            return
        }

        pendingRecording = recording
        askName(activity, TapRecorder.defaultName(appContext)) { name ->
            pendingRecording = null
            save(activity, recording, name)
        }
    }

    /** Recording waiting for its name while the name dialog is shown. */
    private var pendingRecording: TapRecorder.Recording? = null

    /** The name dialog went away without an answer (home button, screen off...): keep the recording anyway. */
    internal fun onNameDialogGone(context: Context) {
        val recording = pendingRecording ?: return
        pendingRecording = null
        val appContext = context.applicationContext
        scope.launch {
            val saved = TapRecorder.save(appContext, recording, null)
            withContext(Dispatchers.Main) {
                if (saved != null) Toast.makeText(appContext, R.string.toast_recorder_saved, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Name the recording before it is saved: renaming it later is harder. Never dismissed without saving. */
    private fun askName(activity: Activity, defaultName: String, onNamed: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(defaultName)
            setSelectAllOnFocus(true)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(NAME_MAX_LENGTH))
            hint = activity.getString(R.string.hint_recording_name)
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_title_recording_name)
            .setMessage(R.string.message_recording_name)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.button_recording_save) { _, _ ->
                onNamed(input.text.toString().trim().ifEmpty { defaultName })
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        input.selectAll()
    }

    private fun save(activity: Activity, recording: TapRecorder.Recording, name: String) {
        val appContext = activity.applicationContext
        scope.launch {
            val saved = TapRecorder.save(appContext, recording, name)
            withContext(Dispatchers.Main) {
                if (saved == null) {
                    Toast.makeText(appContext, R.string.toast_recorder_nothing_recorded, Toast.LENGTH_LONG).show()
                    activity.finish()
                    return@withContext
                }

                Toast.makeText(appContext, R.string.toast_recorder_saved, Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(activity, ScenarioActivity::class.java))
                ScenarioShortcuts.requestPinSmartScenario(activity, saved.first, saved.second)
                activity.finish()
            }
        }
    }

    internal fun cancelRecording(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        TapRecorder.cancel()
        Toast.makeText(context, R.string.toast_recorder_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun showNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_recorder),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_vector)
            .setContentTitle(context.getString(R.string.notification_recorder_title))
            .setContentText(context.getString(R.string.notification_recorder_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.notification_recorder_stop), context.receiverIntent(ACTION_STOP))
            .addAction(0, context.getString(R.string.notification_recorder_cancel), context.receiverIntent(ACTION_CANCEL))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Toast.makeText(context, R.string.toast_recorder_notification_denied, Toast.LENGTH_LONG).show()
        }
    }

    // Activities, not broadcasts: Android forbids starting an activity from a notification broadcast.
    private fun Context.receiverIntent(action: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            action.hashCode(),
            Intent(this, RecorderActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal const val ACTION_STOP = "io.github.vibhor1102.macrion.recorder.STOP"
    internal const val ACTION_CANCEL = "io.github.vibhor1102.macrion.recorder.CANCEL"
}

/** Invisible activity handling the recording notification buttons. */
class RecorderActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (intent?.action) {
            RecorderController.ACTION_STOP -> RecorderController.stopRecording(this)
            RecorderController.ACTION_CANCEL -> {
                RecorderController.cancelRecording(this)
                finish()
            }
            else -> finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing) RecorderController.onNameDialogGone(this)
        super.onDestroy()
    }
}

private const val CHANNEL_ID = "tap_recorder"
private const val NOTIFICATION_ID = 0x5245
private const val NAME_MAX_LENGTH = 60
