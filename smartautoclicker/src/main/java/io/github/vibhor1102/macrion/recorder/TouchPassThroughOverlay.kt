/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.recorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * An invisible window over the screen while recording: it sees each touch of the user (position and movement, in any
 * app, web pages included), gives it to the recorder, then replays the same gesture on the app below.
 *
 * Apps don't always tell the accessibility services what was tapped (web pages often don't), the touches do.
 * The status bar and the navigation bar are left uncovered so the notification shade and the system gestures keep
 * working normally.
 */
internal class TouchPassThroughOverlay(
    private val service: AccessibilityService,
    private val onTouch: (MotionEvent) -> Unit,
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val points = mutableListOf<Triple<Float, Float, Long>>()
    private var forwarding = false

    /** @return false if the window can't be shown. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (view != null) return true
        val metrics = service.resources.displayMetrics
        val top = systemDimension("status_bar_height") ?: (metrics.density * 32).toInt()
        val bottom = systemDimension("navigation_bar_height") ?: (metrics.density * 48).toInt()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (metrics.heightPixels + top + bottom).coerceAtLeast(1), // fixed below, from the real screen height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screenHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.heightPixels
            }
            y = top
            height = (screenHeight - top - bottom).coerceAtLeast(1)
            title = "MacrionTouchRecorder"
        }

        val newView = object : View(service) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) Log.i(TAG, "dispatchTouchEvent down")
                handleTouch(event)
                return true
            }
        }.apply {
            // Some devices don't give touches to a window that never drew anything.
            setBackgroundColor(0x01000000)
        }
        return try {
            windowManager.addView(newView, params)
            view = newView
            Log.i(TAG, "Touch recorder window shown, from y=${params.y} height ${params.height}")
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Can't show the touch recorder window", ex)
            false
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hide() }
            return
        }
        handler.removeCallbacksAndMessages(null)
        view?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (ex: Exception) {
                Log.w(TAG, "Can't remove the touch recorder window", ex)
            }
        }
        view = null
        points.clear()
        forwarding = false
    }

    private fun handleTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.d(TAG, "Touch ${MotionEvent.actionToString(event.actionMasked)} at ${event.rawX},${event.rawY} forwarding=$forwarding")
        }
        if (forwarding) return
        // Only the first finger is replayed.
        onTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                points.add(Triple(event.rawX, event.rawY, event.eventTime))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    points.add(Triple(event.getHistoricalX(0, i) + event.rawX - event.x,
                        event.getHistoricalY(0, i) + event.rawY - event.y, event.getHistoricalEventTime(i)))
                }
                points.add(Triple(event.rawX, event.rawY, event.eventTime))
            }
            MotionEvent.ACTION_UP -> {
                points.add(Triple(event.rawX, event.rawY, event.eventTime))
                forward(points.toList())
                points.clear()
            }
            MotionEvent.ACTION_CANCEL -> points.clear()
        }
    }

    /** Let the touch through: the window stops taking touches while the same gesture is played on the app below. */
    private fun forward(gesture: List<Triple<Float, Float, Long>>) {
        if (gesture.isEmpty()) return
        val first = gesture.first()
        val path = Path().apply { moveTo(first.first.coerceAtLeast(0f), first.second.coerceAtLeast(0f)) }
        // Keep at most ~60 points of the movement, the replay goes through them at the same pace overall.
        val step = (gesture.size / MAX_PATH_POINTS).coerceAtLeast(1)
        gesture.drop(1).filterIndexed { i, _ -> i % step == 0 || i == gesture.size - 2 }.forEach { (x, y, _) ->
            path.lineTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        }
        val duration = (gesture.last().third - first.third).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        setTouchable(false)
        forwarding = true
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Replayed touch cancelled")
                done()
            }
        }
        val dispatched = try {
            service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, duration)).build(),
                callback, handler,
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Can't replay the touch", ex)
            false
        }
        Log.d(TAG, "Replaying ${gesture.size} points over $duration ms: $dispatched")
        if (!dispatched) done()
    }

    private fun done() {
        // Let the app handle the end of the gesture before taking the touches again.
        handler.postDelayed({
            forwarding = false
            setTouchable(true)
        }, RESTORE_DELAY_MS)
    }

    private fun setTouchable(touchable: Boolean) {
        val v = view ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(v, params)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't update the touch recorder window", ex)
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimension(name: String): Int? {
        val id = service.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else null
    }
}

private const val TAG = "TouchPassThroughOverlay"
private const val MAX_PATH_POINTS = 60
private const val MIN_DURATION_MS = 40L
private const val MAX_DURATION_MS = 10_000L
private const val RESTORE_DELAY_MS = 120L
