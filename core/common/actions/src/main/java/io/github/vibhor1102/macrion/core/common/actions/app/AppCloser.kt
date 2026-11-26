/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.core.common.actions.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closes the app the scenario worked in, and only that one, without root: remembers the app the scenario opened or
 * touched, then force stops it from its system "App info" page (like the user would: Force stop, then OK).
 */
@Singleton
internal class AppCloser @Inject constructor() {

    /** Package of the app the running scenario opened or touched last. */
    @Volatile private var automatedPackage: String? = null

    fun reset() {
        automatedPackage = null
    }

    /** The scenario starts an app. */
    fun onAppStarted(service: AccessibilityService, packageName: String?) {
        if (packageName != null && service.isUserApp(packageName)) automatedPackage = packageName
    }

    /** The scenario touches the screen: remember the app in front. Must be called on main thread. */
    fun onScreenTouched(service: AccessibilityService) {
        val pkg = service.frontAppPackage() ?: return
        if (service.isUserApp(pkg)) automatedPackage = pkg
    }

    /** @return the package closed, or null if there was none to close. */
    suspend fun closeAutomatedApp(service: AccessibilityService): String? {
        val pkg = automatedPackage
            ?: withContext(Dispatchers.Main.immediate) { service.frontAppPackage()?.takeIf { service.isUserApp(it) } }
        if (pkg == null) {
            Log.w(TAG, "Close app: no app used by the scenario")
            return null
        }
        Log.i(TAG, "Close app: force stopping $pkg")

        try {
            service.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY)
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Close app: can't open the app info of $pkg", ex)
            return null
        }

        val stopClicked = waitAndClick(service, activeWindowOnly = false, ids = FORCE_STOP_IDS, texts = FORCE_STOP_TEXTS)
        if (stopClicked) {
            // The confirmation dialog: its positive button.
            val confirmed = waitAndClick(service, activeWindowOnly = true, ids = CONFIRM_IDS, texts = CONFIRM_TEXTS)
            Log.i(TAG, "Close app: $pkg force stopped=$confirmed")
        } else {
            Log.i(TAG, "Close app: no enabled force stop button for $pkg (already stopped?)")
            withContext(Dispatchers.Main.immediate) { logScreen(service) }
        }
        delay(SETTLE_MS)
        withContext(Dispatchers.Main.immediate) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        automatedPackage = null
        return pkg
    }

    private suspend fun waitAndClick(
        service: AccessibilityService,
        activeWindowOnly: Boolean,
        ids: List<String>,
        texts: List<String>,
    ): Boolean {
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val clicked = withContext(Dispatchers.Main.immediate) {
                val roots = if (activeWindowOnly) listOfNotNull(service.rootInActiveWindow) else service.settingsRoots()
                roots.any { root -> root.clickFirst(ids, texts) }
            }
            if (clicked) return true
        }
        return false
    }

    /** What is on the screen when the button is not found, to know why on a phone. */
    private fun logScreen(service: AccessibilityService) {
        val active = service.rootInActiveWindow
        Log.i(TAG, "Close app: active window ${active?.packageName}, windows: " +
            (try { service.windows?.joinToString { "${it.type}:${it.root?.packageName}" } } catch (_: Exception) { null }))
        val lines = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || lines.size >= 40) return
            val text = node.text ?: node.contentDescription
            if (!text.isNullOrBlank() || node.isClickable) {
                lines += "'${text ?: ""}' id=${node.viewIdResourceName} click=${node.isClickable} enabled=${node.isEnabled}"
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        service.settingsRoots().forEach { walk(it, 0) }
        lines.forEach { Log.i(TAG, "Close app: node $it") }
    }

    private fun AccessibilityService.settingsRoots(): List<AccessibilityNodeInfo> {
        val roots = try { windows?.mapNotNull { it.root } } catch (_: Exception) { null } ?: emptyList()
        return (listOfNotNull(rootInActiveWindow) + roots).filter { it.packageName?.toString() != packageName }
    }

    private fun AccessibilityNodeInfo.clickFirst(ids: List<String>, texts: List<String>): Boolean {
        val byId = ids.asSequence().flatMap { id -> findAccessibilityNodeInfosByViewId(id).orEmpty().asSequence() }
        val byText = texts.asSequence().flatMap { text ->
            findAccessibilityNodeInfosByText(text).orEmpty().asSequence()
                .filter { node -> node.text?.toString()?.trim()?.equals(text, ignoreCase = true) == true }
        }
        for (node in byId + byText) {
            var clickable: AccessibilityNodeInfo? = node
            var depth = 0
            while (clickable != null && !clickable.isClickable && depth < MAX_PARENT_DEPTH) {
                clickable = clickable.parent
                depth++
            }
            if (clickable == null || !clickable.isClickable) continue
            if (!clickable.isEnabled || !node.isEnabled) continue
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Close app: clicked '${node.text ?: node.viewIdResourceName}'")
                return true
            }
        }
        return false
    }

    private fun AccessibilityService.frontAppPackage(): String? {
        val active = try { windows?.firstOrNull { it.isActive } } catch (_: Exception) { null }
        if (active != null && active.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        return try { (active?.root ?: rootInActiveWindow)?.packageName?.toString() } catch (_: Exception) { null }
    }

    private fun AccessibilityService.isUserApp(pkg: String): Boolean {
        if (pkg == packageName || pkg in SYSTEM_PACKAGES || SYSTEM_PACKAGE_PARTS.any { pkg.contains(it) }) return false
        return pkg !in launcherPackages()
    }

    private var launchers: Set<String>? = null
    private fun AccessibilityService.launcherPackages(): Set<String> = launchers ?: try {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }.also { launchers = it }
}

private val FORCE_STOP_IDS = listOf(
    "com.android.settings:id/force_stop_button",
    "com.android.settings:id/button_force_stop",
)
private val FORCE_STOP_TEXTS = listOf("Force stop", "강제 중지", "강제 종료", "강제중지")
private val CONFIRM_IDS = listOf("android:id/button1")
private val CONFIRM_TEXTS = listOf("OK", "확인")

private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "com.android.settings")
private val SYSTEM_PACKAGE_PARTS = listOf("samsungpass", "biometrics", "authfw", "launcher")
private const val FIND_TIMEOUT_MS = 8_000L
private const val POLL_MS = 250L
private const val SETTLE_MS = 600L
private const val MAX_PARENT_DEPTH = 4
private const val TAG = "AppCloser"
