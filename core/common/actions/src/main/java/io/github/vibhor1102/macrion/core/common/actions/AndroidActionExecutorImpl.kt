/*
 * Copyright (C) 2025 Kevin Buzeau
 * Copyright (C) 2026 Vibhor Goel
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
package io.github.vibhor1102.macrion.core.common.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.AndroidRuntimeException
import android.util.Log

import io.github.vibhor1102.macrion.core.common.actions.gesture.GestureExecutor
import io.github.vibhor1102.macrion.core.common.actions.gesture.GestureDispatchResult
import io.github.vibhor1102.macrion.core.common.actions.model.ActionNotificationRequest
import io.github.vibhor1102.macrion.core.common.actions.notification.NotificationRequestExecutor
import io.github.vibhor1102.macrion.core.common.actions.text.TextExecutor
import io.github.vibhor1102.macrion.core.common.actions.element.ElementLookup
import io.github.vibhor1102.macrion.core.common.actions.element.ElementSelectorKind
import io.github.vibhor1102.macrion.core.common.actions.element.ElementTapExecutor
import android.graphics.Path
import kotlinx.coroutines.delay
import io.github.vibhor1102.macrion.core.common.actions.external.ExternalActionEventContract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton


import io.github.vibhor1102.macrion.core.common.actions.sound.SoundExecutor
import io.github.vibhor1102.macrion.core.common.actions.screenshot.ScreenshotExecutor

@Singleton
internal class AndroidActionExecutorImpl @Inject constructor(
    private val gestureExecutor: GestureExecutor,
    private val notificationRequestExecutor: NotificationRequestExecutor,
    private val textExecutor: TextExecutor,
    private val elementTapExecutor: ElementTapExecutor,
    private val soundExecutor: SoundExecutor,
    private val screenshotExecutor: ScreenshotExecutor,
) : AndroidActionExecutor {

    /** Keep the service in a week reference to avoid potential leak. */
    private var accessibilityServiceRef: WeakReference<AccessibilityService>? = null
    private var onGestureFailure: ((AccessibilityService) -> Unit)? = null
    private val accessibilityService: AccessibilityService?
        get() {
            val ref = accessibilityServiceRef ?: let {
                Log.w(TAG, "Can't get accessibility service, init has not been called")
                return null
            }
            return ref.get() ?: let {
                Log.w(TAG, "Can't get accessibility service, it has been destroyed")
                null
            }
        }

    override fun init(service: AccessibilityService, onGestureFailure: (AccessibilityService) -> Unit) {
        gestureExecutor.resetConnection()
        accessibilityServiceRef = WeakReference(service)
        this.onGestureFailure = onGestureFailure
        notificationRequestExecutor.init(service)
    }

    override fun resetState() {
        gestureExecutor.clear()
        notificationRequestExecutor.clear()
        soundExecutor.stopSound()
        screenshotExecutor.resetState()
    }

    override fun clear() {
        gestureExecutor.resetConnection()
        resetState()
        soundExecutor.clear()
        accessibilityServiceRef = null
        onGestureFailure = null
    }

    override suspend fun dispatchGesture(gestureDescription: GestureDescription): Unit = withContext(Dispatchers.Main.immediate) {
        val service = accessibilityService
        val result = service?.let { gestureExecutor.dispatchGesture(it, gestureDescription) }
        currentCoroutineContext().ensureActive()
        if (result != GestureDispatchResult.COMPLETED) {
            Log.w(TAG, "Pausing execution after unresolved gesture result: $result")
            if (service != null) onGestureFailure?.invoke(service)
            // Do not advance to later actions, and do not turn a user Stop into an automatic retry.
            throw CancellationException("Gesture did not complete: $result")
        }
    }

    override fun performGlobalAction(globalAction: Int) {
        val service = accessibilityService ?: return

        try {
            service.performGlobalAction(globalAction)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't execute global action.", ex)
        }
    }

    override fun writeTextOnFocusedItem(text: String, validate: Boolean) {
        val service = accessibilityService ?: return

        textExecutor.writeText(service, text, validate)
    }

    override suspend fun tapElement(kind: ElementSelectorKind, value: String, timeoutMs: Long): Boolean {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + timeoutMs.coerceAtLeast(0L)
        // true: scrolling down, false: scrolling back up, null: done scrolling.
        var scrollDirection: Boolean? = true
        // Start of the wait for the element in the app itself, after a fingerprint prompt for example.
        var waitStartMs = startMs
        var lastTapAt: ElementLookup.TapAt? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val service = accessibilityService ?: return false
            val lookup = withContext(Dispatchers.Main.immediate) {
                elementTapExecutor.findAndClick(service, kind, value)
            }

            when (lookup) {
                ElementLookup.Clicked -> return true
                is ElementLookup.TapAt -> {
                    // A page still moving (end of a scroll) gives another position on the next look: tap once it
                    // stays in place.
                    val previous = lastTapAt
                    lastTapAt = lookup
                    val moved = previous == null ||
                        kotlin.math.abs(previous.x - lookup.x) > ELEMENT_STILL_SLOP_PX ||
                        kotlin.math.abs(previous.y - lookup.y) > ELEMENT_STILL_SLOP_PX
                    if (moved && System.currentTimeMillis() < deadline) {
                        delay(ELEMENT_STILL_CHECK_MS)
                        continue
                    }
                    val path = Path().apply { moveTo(lookup.x, lookup.y) }
                    dispatchGesture(
                        GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0L, ELEMENT_TAP_DURATION_MS))
                            .build()
                    )
                    return true
                }
                ElementLookup.ScrolledTo -> {
                    if (System.currentTimeMillis() >= deadline) {
                        Log.i(TAG, "Element still out of the screen at timeout: $kind '$value'")
                        return false
                    }
                    delay(ELEMENT_SCROLL_SETTLE_MS)
                    continue
                }
                ElementLookup.NotFound -> Unit
            }

            if (System.currentTimeMillis() >= deadline) {
                Log.i(TAG, "Element not found before timeout: $kind '$value'")
                return false
            }

            // Not on the screen after a while: it may be lower in a list or a web page. Scroll down page by page, then
            // back up once the bottom is reached, like the user would.
            val appInFront = withContext(Dispatchers.Main.immediate) { elementTapExecutor.isAppInFront(service) }
            if (!appInFront) waitStartMs = System.currentTimeMillis()
            if (appInFront && scrollDirection != null && System.currentTimeMillis() - waitStartMs >= ELEMENT_SCROLL_SEARCH_AFTER_MS) {
                val forward = scrollDirection == true
                val scrolled = withContext(Dispatchers.Main.immediate) { elementTapExecutor.scrollScreen(service, forward) }
                if (scrolled) {
                    delay(ELEMENT_SCROLL_SETTLE_MS)
                    continue
                }
                scrollDirection = if (forward) false else null
            }
            delay(ELEMENT_POLL_INTERVAL_MS)
        }
    }

    override fun startActivity(intent: Intent) {
        val service = accessibilityService ?: return

        try {
            service.startActivity(intent)
        } catch (anfe: ActivityNotFoundException) {
            Log.w(TAG, "Can't start activity, it is not found.", anfe)
        } catch (arex: AndroidRuntimeException) {
            Log.w(TAG, "Can't start activity, Intent is invalid: $intent", arex)
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't start activity, Intent contains invalid arguments: $intent", iaex)
        } catch (secEx: SecurityException) {
            Log.w(TAG, "Can't start activity with intent $intent, permission is denied by the system", secEx)
        } catch (npe: NullPointerException) {
            Log.w(TAG, "Can't start activity with intent $intent, intent is invalid", npe)
        }
    }

    override fun sendBroadcast(intent: Intent) {
        val service = accessibilityService ?: return

        try {
            service.sendBroadcast(intent)
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't send broadcast, Intent is invalid: $intent", iaex)
        }
    }

    override fun postNotification(notificationRequest: ActionNotificationRequest) {
        accessibilityService ?: return // No need for service here, but init state is bound to it
        notificationRequestExecutor.postNotification(notificationRequest)
    }

    override fun fireExternalAction(externalActionName: String) {
        val service = accessibilityService ?: return

        try {
            service.sendBroadcast(ExternalActionEventContract.createRequestQueryIntent(externalActionName))
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't fire external action, Intent is invalid.", iaex)
        }
    }

    override suspend fun playSound(soundUri: String) {
        soundExecutor.playSound(soundUri)
    }

    override suspend fun captureScreenshot(folderUri: String?, folderName: String?): Boolean {
        return screenshotExecutor.captureScreenshot(folderUri, folderName)
    }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        gestureExecutor.dump(writer, prefix)
    }
}

private const val TAG = "ServiceActionExecutor"

private const val ELEMENT_POLL_INTERVAL_MS = 250L
private const val ELEMENT_TAP_DURATION_MS = 50L
private const val ELEMENT_SCROLL_SEARCH_AFTER_MS = 1_500L
private const val ELEMENT_SCROLL_SETTLE_MS = 600L
private const val ELEMENT_STILL_CHECK_MS = 200L
private const val ELEMENT_STILL_SLOP_PX = 8f
