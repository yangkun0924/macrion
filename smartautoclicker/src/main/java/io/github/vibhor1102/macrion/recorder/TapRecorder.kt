/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.recorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent as AndroidIntent
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

import io.github.vibhor1102.macrion.core.base.identifier.DATABASE_ID_INSERTION
import io.github.vibhor1102.macrion.core.base.identifier.Identifier
import io.github.vibhor1102.macrion.core.domain.IRepository
import io.github.vibhor1102.macrion.core.domain.model.AND
import io.github.vibhor1102.macrion.core.domain.model.action.Action
import io.github.vibhor1102.macrion.core.domain.model.action.Click
import io.github.vibhor1102.macrion.core.domain.model.action.Intent
import io.github.vibhor1102.macrion.core.domain.model.action.Swipe
import io.github.vibhor1102.macrion.core.domain.model.action.TapElement
import io.github.vibhor1102.macrion.core.domain.model.action.ToggleEvent
import io.github.vibhor1102.macrion.core.domain.model.condition.TriggerCondition
import io.github.vibhor1102.macrion.core.domain.model.event.TriggerEvent
import io.github.vibhor1102.macrion.core.domain.model.scenario.Scenario

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the taps made by the user in other apps, and turns them into a one shot smart scenario:
 * launch the first app, then tap each element (by text, resource id or description, coordinates as a last resort).
 *
 * The accessibility service feeds it with [onAccessibilityEvent]; recording only listens to clicks and window changes
 * while active, the service configuration is restored afterwards.
 */
object TapRecorder {

    /** One recorded user step. */
    sealed class Step {
        data class Element(val type: TapElement.SelectorType, val value: String, val label: String) : Step()
        data class Position(val x: Int, val y: Int) : Step()
        data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long) : Step()
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    private var serviceRef: WeakReference<AccessibilityService>? = null
    private var repository: IRepository? = null
    private var originalEventTypes: Int = 0
    private var originalFlags: Int = 0

    private var ignoredPackages: Set<String> = emptySet()
    private var startPackage: String? = null
    private val steps = mutableListOf<Step>()

    /**
     * The touches themselves are seen through [TouchPassThroughOverlay], so taps and swipes are recorded in any app,
     * web pages included, which often don't report their clicks to accessibility services.
     */
    private var observingTouches = false
    private var touchOverlay: TouchPassThroughOverlay? = null
    private var touchDown: TouchDown? = null
    private var lastScrollStepMs = 0L
    private var lastScrollForward: Boolean? = null

    private class TouchDown(val x: Float, val y: Float, val timeMs: Long, val target: TouchTarget?) {
        var maxDistance = 0f
        var multiTouch = false
    }

    /** What is under a finger when it touches the screen: the app, and how to find the element again. */
    private class TouchTarget(val pkg: String, val step: Step)

    /** Called by the accessibility service once connected. */
    fun attach(service: AccessibilityService, smartRepository: IRepository) {
        serviceRef = WeakReference(service)
        repository = smartRepository
    }

    fun detach() {
        if (_isRecording.value) cancel()
        serviceRef = null
        repository = null
    }

    fun isServiceAvailable(): Boolean = serviceRef?.get() != null

    /** Start listening to user taps. @return false if the accessibility service is not running. */
    fun start(context: Context): Boolean {
        val service = serviceRef?.get() ?: return false
        if (_isRecording.value) return true

        steps.clear()
        _stepCount.value = 0
        startPackage = null
        ignoredPackages = buildSet {
            add(context.packageName)
            addAll(SYSTEM_OVERLAY_PACKAGES)
            getHomePackages(context).forEach(::add)
            getInputMethodPackages(context).forEach(::add)
            getAutofillPackage(context)?.let(::add)
        }

        Log.i(TAG, "Ignored packages: $ignoredPackages")
        // Some devices return a null service info, start from a fresh one then.
        val info = service.serviceInfo ?: AccessibilityServiceInfo()
        originalEventTypes = info.eventTypes
        originalFlags = info.flags
        info.eventTypes = info.eventTypes or RECORDED_EVENT_TYPES
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        service.serviceInfo = info
        touchOverlay = TouchPassThroughOverlay(service, ::onMotionEvent)
        observingTouches = touchOverlay?.show() == true
        touchDown = null
        lastScrollStepMs = 0L
        lastScrollForward = null

        _isRecording.value = true
        Log.i(TAG, "Recording started, observing touches: $observingTouches")
        return true
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!_isRecording.value || event == null) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "Event ${AccessibilityEvent.eventTypeToString(event.eventType)} from $pkg")
        if (pkg in ignoredPackages || pkg.isSystemOverlayPackage()) return

        // The touches give the taps and swipes when they are observed, the click and scroll events would repeat them.
        if (observingTouches) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event, pkg)
            return
        }

        // The app to launch is the one of the first real tap made in a regular app window. Window changes are not used
        // for it: on some devices (Samsung Pass, autofill, keyboards, edge panels...) a system window shows up first.
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (!event.isInApplicationWindow()) {
            Log.d(TAG, "Click ignored: not in an application window")
            return
        }

        if (startPackage == null) startPackage = pkg
        val step = event.toStep() ?: return Unit.also { Log.d(TAG, "Click ignored: no usable selector") }
        steps.add(step)
        _stepCount.value = steps.size
        Log.d(TAG, "Recorded $step in $pkg")
    }

    /**
     * Called with the touches seen by [TouchPassThroughOverlay]. A touch that barely moves is a tap on the element under the
     * finger, found when the finger goes down (the screen changes right after the tap). Other touches are swipes.
     */
    fun onMotionEvent(event: MotionEvent) {
        if (!_isRecording.value || !observingTouches) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchesSeen = true
                touchDown = TouchDown(event.rawX, event.rawY, SystemClock.uptimeMillis(), targetAt(event.rawX.toInt(), event.rawY.toInt()))
            }
            MotionEvent.ACTION_POINTER_DOWN -> touchDown?.multiTouch = true
            MotionEvent.ACTION_MOVE -> touchDown?.let { down ->
                down.maxDistance = maxOf(down.maxDistance, distance(down.x, down.y, event.rawX, event.rawY))
            }
            MotionEvent.ACTION_UP -> {
                val down = touchDown ?: return
                touchDown = null
                down.maxDistance = maxOf(down.maxDistance, distance(down.x, down.y, event.rawX, event.rawY))
                onTouchEnded(down, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_CANCEL -> touchDown = null
        }
    }

    private var touchesSeen = false

    private fun onTouchEnded(down: TouchDown, upX: Float, upY: Float) {
        Log.d(TAG, "Touch ended, moved ${down.maxDistance}px, target ${down.target?.pkg}")
        val target = down.target ?: return Unit.also { Log.d(TAG, "Touch ignored: not in a recorded app") }
        if (down.multiTouch) return Unit.also { Log.d(TAG, "Touch ignored: several fingers") }
        val slop = serviceRef?.get()?.resources?.displayMetrics?.density?.times(TAP_SLOP_DP) ?: 24f

        val step = if (down.maxDistance <= slop) {
            target.step
        } else {
            Step.Drag(
                down.x.toInt(), down.y.toInt(), upX.toInt(), upY.toInt(),
                (SystemClock.uptimeMillis() - down.timeMs).coerceIn(SWIPE_MIN_DURATION_MS, SWIPE_MAX_DURATION_MS),
            )
        }
        if (startPackage == null) startPackage = target.pkg
        steps.add(step)
        _stepCount.value = steps.size
        Log.d(TAG, "Recorded $step in ${target.pkg}")
    }

    /** The app window under a point and the element there, or null for system windows, keyboards and this app. */
    private fun targetAt(x: Int, y: Int): TouchTarget? {
        val service = serviceRef?.get() ?: return null
        val windows = try { service.windows } catch (_: Exception) { null } ?: return null
        val window = windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && Rect().also(it::getBoundsInScreen).contains(x, y) }
            .maxByOrNull { it.layer } ?: return null
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            Log.d(TAG, "Touch in window of type ${window.type}, ignored")
            return null
        }
        val root = window.root ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg in ignoredPackages || pkg.isSystemOverlayPackage()) {
            Log.d(TAG, "Touch in $pkg, ignored")
            return null
        }
        val node = root.deepestAt(x, y)
        val step = node?.toStep("") ?: Step.Position(x, y)
        return TouchTarget(pkg, step)
    }

    /** The smallest visible element containing the point. */
    private fun AccessibilityNodeInfo.deepestAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(this to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!node.isVisibleToUser || !bounds.contains(x, y)) continue
            val area = bounds.width().toLong() * bounds.height()
            if (area <= bestArea && node.hasSelector()) {
                best = node
                bestArea = area
            }
            if (depth >= TREE_MAX_DEPTH) continue
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        return best
    }

    private fun AccessibilityNodeInfo.hasSelector(): Boolean =
        text?.toString()?.trim().orEmpty().isUsableText() ||
            !contentDescription?.toString()?.trim().isNullOrEmpty() ||
            !viewIdResourceName.isNullOrBlank()

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

    /** Without the touches (the recorder window can't be shown), a scrolled list or page becomes a swipe over it. */
    private fun onScrolled(event: AccessibilityEvent, pkg: String) {
        if (!event.isInApplicationWindow()) return
        val node = event.source ?: return
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.height() < MIN_SCROLL_VIEW_SIZE || bounds.width() < MIN_SCROLL_VIEW_SIZE) return
        val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val deltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaX else 0
        if (deltaX != 0 && deltaY == 0) return // carousels: not handled
        val forward = deltaY >= 0 || deltaY == -1
        val now = SystemClock.uptimeMillis()
        // One user swipe sends many scroll events: one swipe step per gesture.
        if (now - lastScrollStepMs < SCROLL_MERGE_MS && lastScrollForward == forward && steps.lastOrNull() is Step.Drag) {
            lastScrollStepMs = now
            return
        }
        lastScrollStepMs = now
        lastScrollForward = forward
        val x = bounds.centerX()
        val top = bounds.top + bounds.height() / 4
        val bottom = bounds.bottom - bounds.height() / 4
        val step = if (forward) Step.Drag(x, bottom, x, top, SCROLL_SWIPE_DURATION_MS) else Step.Drag(x, top, x, bottom, SCROLL_SWIPE_DURATION_MS)
        if (startPackage == null) startPackage = pkg
        steps.add(step)
        _stepCount.value = steps.size
        Log.d(TAG, "Recorded scroll $step in $pkg")
    }

    /** False for keyboards, system windows (biometric prompts, shade...) and overlays, true otherwise. */
    private fun AccessibilityEvent.isInApplicationWindow(): Boolean {
        val windows = try { serviceRef?.get()?.windows } catch (_: Exception) { null } ?: return true
        val window = windows.firstOrNull { it.id == windowId } ?: return true
        Log.d(TAG, "Click in window $windowId of type ${window.type}")
        return window.type !in NOT_APP_WINDOW_TYPES
    }

    /** Stop recording without saving. */
    fun cancel() {
        restoreServiceInfo()
        steps.clear()
        _stepCount.value = 0
        _isRecording.value = false
    }

    /**
     * Stop recording and save the scenario.
     * @return the id and name of the created scenario, or null if nothing was recorded or save failed.
     */
    suspend fun stopAndSave(context: Context, scenarioName: String? = null): Pair<Long, String>? {
        if (!_isRecording.value) return null
        restoreServiceInfo()
        _isRecording.value = false

        val recordedSteps = steps.toList()
        val pkg = startPackage
        steps.clear()
        _stepCount.value = 0
        val repo = repository ?: return null
        Log.i(TAG, "Stop recording: ${recordedSteps.size} steps, app to launch $pkg")
        if (recordedSteps.isEmpty() && pkg == null) return null

        val name = scenarioName ?: (context.getString(io.github.vibhor1102.macrion.R.string.recorded_scenario_name) +
                " " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()))

        val scenarioId = repo.addScenario(
            Scenario(id = Identifier(databaseId = DATABASE_ID_INSERTION, tempId = 0L), name = name, detectionQuality = 1200)
        )
        val scenario = repo.getScenario(scenarioId) ?: return null
        val event = buildEvent(context, scenario, pkg, recordedSteps)
        val saved = repo.updateScenario(scenario, listOf(event), emptyList())
        Log.i(TAG, "Scenario $scenarioId '$name' saved=$saved with ${event.actions.size} actions")
        return if (saved) scenarioId to name else null
    }

    private fun buildEvent(context: Context, scenario: Scenario, pkg: String?, steps: List<Step>): TriggerEvent {
        var tempId = 0L
        fun newId() = Identifier(databaseId = DATABASE_ID_INSERTION, tempId = ++tempId)
        val eventId = newId()
        val actions = mutableListOf<Action>()

        pkg?.let { launchComponent(context, it) }?.let { component ->
            actions += Intent(
                id = newId(), eventId = eventId, name = context.getString(io.github.vibhor1102.macrion.R.string.recorded_step_launch_app),
                priority = actions.size, isAdvanced = false, isBroadcast = false,
                intentAction = AndroidIntent.ACTION_MAIN,
                flags = AndroidIntent.FLAG_ACTIVITY_NEW_TASK or AndroidIntent.FLAG_ACTIVITY_CLEAR_TOP,
                componentName = component,
            )
        }

        steps.forEachIndexed { index, step ->
            actions += when (step) {
                is Step.Element -> TapElement(
                    id = newId(), eventId = eventId, name = "${index + 1}. ${step.label}".take(NAME_MAX_LENGTH),
                    priority = actions.size, selectorType = step.type, selectorValue = step.value,
                    // The app may first ask for a fingerprint or a PIN when opened: leave the user time for it.
                    timeoutMs = if (index == 0) FIRST_TAP_TIMEOUT_MS else RECORDED_TAP_TIMEOUT_MS,
                )
                is Step.Drag -> Swipe(
                    id = newId(), eventId = eventId, name = "${index + 1}. " +
                        context.getString(io.github.vibhor1102.macrion.R.string.recorded_step_swipe),
                    priority = actions.size, swipeDuration = step.durationMs,
                    from = Point(step.fromX, step.fromY), to = Point(step.toX, step.toY),
                    // Let the screen settle after the previous step, and the scroll end before the next one.
                    waitBeforeMs = SWIPE_WAIT_BEFORE_MS, waitAfterMs = SWIPE_WAIT_AFTER_MS,
                )
                is Step.Position -> Click(
                    id = newId(), eventId = eventId, name = "${index + 1}. (${step.x}, ${step.y})",
                    priority = actions.size, pressDuration = POSITION_PRESS_DURATION_MS,
                    positionType = Click.PositionType.USER_SELECTED, position = Point(step.x, step.y),
                    waitBeforeMs = POSITION_WAIT_BEFORE_MS,
                )
            }
        }

        // One shot: disable all events once done, which stops the scenario.
        actions += ToggleEvent(
            id = newId(), eventId = eventId, name = context.getString(io.github.vibhor1102.macrion.R.string.recorded_step_finish),
            priority = actions.size, toggleAll = true, toggleAllType = ToggleEvent.ToggleType.DISABLE,
        )

        return TriggerEvent(
            id = eventId,
            scenarioId = scenario.id,
            name = context.getString(io.github.vibhor1102.macrion.R.string.recorded_event_name),
            conditionOperator = AND,
            actions = actions,
            conditions = listOf(
                TriggerCondition.OnTimerReached(
                    id = newId(), eventId = eventId,
                    name = context.getString(io.github.vibhor1102.macrion.R.string.recorded_condition_name),
                    durationMs = START_DELAY_MS, restartWhenReached = false,
                )
            ),
            enabledOnStart = true,
        )
    }

    private fun AccessibilityEvent.toStep(): Step? {
        // The event text holds the texts of all the children of the clicked view (e.g. title + summary of a list
        // row): only use it when it is a single text, joined texts can't be found on screen when playing.
        val eventText = text.singleOrNull()?.toString()?.trim().orEmpty()
        val node = source
        if (node == null) {
            if (eventText.isUsableText()) return Step.Element(TapElement.SelectorType.TEXT_EXACT, eventText, eventText)
            val desc = contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) return Step.Element(TapElement.SelectorType.CONTENT_DESCRIPTION, desc, desc)
            return null
        }
        return node.toStep(eventText)
    }

    private fun AccessibilityNodeInfo.toStep(eventText: String): Step? {
        val node: AccessibilityNodeInfo? = this

        val nodeText = node?.text?.toString()?.trim().orEmpty().ifEmpty { eventText }
        if (nodeText.isUsableText()) return Step.Element(TapElement.SelectorType.TEXT_EXACT, nodeText, nodeText)

        node?.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { id ->
            return Step.Element(TapElement.SelectorType.RESOURCE_ID, id, id.substringAfter(":id/"))
        }

        val desc = node?.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty()) return Step.Element(TapElement.SelectorType.CONTENT_DESCRIPTION, desc, desc)

        node?.findChildText()?.let { childText ->
            return Step.Element(TapElement.SelectorType.TEXT_EXACT, childText, childText)
        }

        if (node == null) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return null
        return Step.Position(bounds.centerX(), bounds.centerY())
    }

    private fun AccessibilityNodeInfo.findChildText(): String? {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in childCount - 1 downTo 0) getChild(i)?.let { stack.add(it to 1) }
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isUsableText()) return text
            if (depth >= CHILD_TEXT_MAX_DEPTH) continue
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        return null
    }

    private fun String.isUsableText(): Boolean = isNotEmpty() && length <= TEXT_MAX_LENGTH

    private fun restoreServiceInfo() {
        touchOverlay?.hide()
        touchOverlay = null
        observingTouches = false
        touchesSeen = false
        touchDown = null
        val service = serviceRef?.get() ?: return
        // Only remove the event types added for the recording: other flags may have changed meanwhile.
        service.serviceInfo?.let { info ->
            info.eventTypes = originalEventTypes
            info.flags = originalFlags
            service.serviceInfo = info
        }
    }

    /**
     * The launcher(s). Not every app with a HOME activity: Settings has one (FallbackHome) and it must be recordable,
     * so apps that can be opened from the launcher are kept.
     */
    private fun getHomePackages(context: Context): List<String> {
        val pm = context.packageManager
        return pm.queryIntentActivities(AndroidIntent(AndroidIntent.ACTION_MAIN).addCategory(AndroidIntent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { pm.getLaunchIntentForPackage(it) == null }
    }

    private fun getInputMethodPackages(context: Context): List<String> =
        try {
            context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }.orEmpty()
        } catch (ex: Exception) {
            emptyList()
        }

    private fun getAutofillPackage(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, "autofill_service")
            ?.let(ComponentName::unflattenFromString)?.packageName

    private fun String.isSystemOverlayPackage(): Boolean =
        SYSTEM_OVERLAY_PREFIXES.any { startsWith(it) }

    private fun launchComponent(context: Context, pkg: String): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.component
}

private const val TAG = "TapRecorder"

private val NOT_APP_WINDOW_TYPES = setOf(
    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
    AccessibilityWindowInfo.TYPE_SYSTEM,
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
)

/** Packages showing system windows over other apps, never the app the user wants to automate. */
private val SYSTEM_OVERLAY_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.google.android.gms",
    "com.sec.android.app.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.samsung.android.samsungpass",
    "com.samsung.android.samsungpassautofill",
    "com.samsung.android.authfw",
    "com.samsung.android.biometrics.app.setting",
    "com.samsung.android.app.cocktailbarservice",
    "com.samsung.android.honeyboard",
    "com.samsung.android.bixby.agent",
    "com.samsung.android.app.smartcapture",
)
private val SYSTEM_OVERLAY_PREFIXES = listOf("com.samsung.android.samsungpass", "com.samsung.android.biometrics")
private const val RECORDED_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
    AccessibilityEvent.TYPE_VIEW_SCROLLED
private const val TAP_SLOP_DP = 12f
private const val TREE_MAX_DEPTH = 60
private const val SWIPE_MIN_DURATION_MS = 100L
private const val SWIPE_MAX_DURATION_MS = 2_000L
private const val SWIPE_WAIT_BEFORE_MS = 1_000L
private const val SWIPE_WAIT_AFTER_MS = 800L
private const val SCROLL_SWIPE_DURATION_MS = 400L
private const val SCROLL_MERGE_MS = 700L
private const val MIN_SCROLL_VIEW_SIZE = 100
private const val TEXT_MAX_LENGTH = 40
private const val NAME_MAX_LENGTH = 50
private const val CHILD_TEXT_MAX_DEPTH = 4
private const val RECORDED_TAP_TIMEOUT_MS = 10_000L
private const val FIRST_TAP_TIMEOUT_MS = 30_000L
private const val START_DELAY_MS = 300L
private const val POSITION_PRESS_DURATION_MS = 50L
private const val POSITION_WAIT_BEFORE_MS = 1_500L
