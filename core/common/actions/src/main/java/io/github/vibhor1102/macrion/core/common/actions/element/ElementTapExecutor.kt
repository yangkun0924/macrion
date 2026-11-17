/*
 * Copyright (C) 2026 Macrion contributors
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
package io.github.vibhor1102.macrion.core.common.actions.element

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import javax.inject.Inject
import javax.inject.Singleton

/** How to identify a UI element in the accessibility tree. */
enum class ElementSelectorKind { TEXT_EXACT, TEXT_CONTAINS, RESOURCE_ID, CONTENT_DESCRIPTION }

/** Result of a lookup of a UI element on the screen. */
sealed class ElementLookup {
    /** The element was clicked through the accessibility ACTION_CLICK. */
    data object Clicked : ElementLookup()
    /** The element was found but can't be clicked via accessibility; tap its center with a gesture instead. */
    data class TapAt(val x: Float, val y: Float) : ElementLookup()
    /** The element exists but is out of the screen (e.g. lower in a list or a web page): it was scrolled to. */
    data object ScrolledTo : ElementLookup()
    /** The element is not on the screen. */
    data object NotFound : ElementLookup()
}

@Singleton
internal class ElementTapExecutor @Inject constructor() {

    /** Look for the element once in the current windows, and click it if possible. Must be called on main thread. */
    fun findAndClick(service: AccessibilityService, kind: ElementSelectorKind, value: String): ElementLookup {
        val query = value.trim()
        if (query.isEmpty()) return ElementLookup.NotFound

        val screen = service.screenBounds()
        var offscreen: AccessibilityNodeInfo? = null
        for (root in service.candidateRoots()) {
            val node = root.findMatch(service.packageName, kind, query, screen) ?: continue
            if (!node.isOnScreen(screen)) {
                if (offscreen == null) offscreen = node
                continue
            }

            // Prefer the accessibility click on the element or its closest clickable parent.
            var clickable: AccessibilityNodeInfo? = node
            var depth = 0
            while (clickable != null && !clickable.isClickable && depth < MAX_PARENT_DEPTH) {
                clickable = clickable.parent
                depth++
            }
            if (clickable != null && clickable.isClickable && clickable.isEnabled &&
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ElementLookup.Clicked
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            return ElementLookup.TapAt(bounds.exactCenterX(), bounds.exactCenterY())
        }

        offscreen?.let { node ->
            if (node.bringOnScreen(screen)) return ElementLookup.ScrolledTo
        }
        return ElementLookup.NotFound
    }

    /**
     * Scroll the biggest scrollable view of the screen (a list, a web page...) one page, to look for an element that
     * is not loaded yet. Must be called on main thread.
     * @return true if something was scrolled.
     */
    fun scrollScreen(service: AccessibilityService, forward: Boolean): Boolean {
        val screen = service.screenBounds()
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scrollables = service.candidateRoots()
            .flatMap { it.findAll(service.packageName) { node -> node.isScrollable && node.isOnScreen(screen) } }
            .sortedByDescending { node -> Rect().also(node::getBoundsInScreen).let { it.width() * it.height() } }
        val scrolled = scrollables.any { it.performAction(action) }
        Log.d(TAG, "Scroll ${if (forward) "forward" else "backward"} among ${scrollables.size} views: $scrolled")
        return scrolled
    }

    /**
     * True if the user is looking at a regular app, false while a system window is in front of it (fingerprint or
     * PIN prompt, lock screen, notification shade...): nothing must be scrolled behind it.
     */
    fun isAppInFront(service: AccessibilityService): Boolean {
        val active = try {
            service.windows?.firstOrNull { it.isActive }
        } catch (_: Exception) {
            null
        } ?: return true
        if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION) return false
        val pkg = active.root?.packageName?.toString() ?: return true
        return pkg != service.packageName && pkg !in SYSTEM_PACKAGES && SYSTEM_PACKAGE_PARTS.none { pkg.contains(it) }
    }

    /** Scroll an element found out of the screen into it: by asking it directly, else by scrolling its parent list. */
    private fun AccessibilityNodeInfo.bringOnScreen(screen: Rect): Boolean {
        if (performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
            Log.d(TAG, "Element shown on screen")
            return true
        }
        val bounds = Rect().also(::getBoundsInScreen)
        val forward = bounds.top >= screen.bottom || bounds.isEmpty
        var parent = parent
        var depth = 0
        while (parent != null && depth < MAX_TREE_DEPTH) {
            if (parent.isScrollable && parent.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )) {
                Log.d(TAG, "Element parent scrolled, forward=$forward")
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun AccessibilityNodeInfo.isOnScreen(screen: Rect): Boolean {
        if (!isVisibleToUser) return false
        val bounds = Rect().also(::getBoundsInScreen)
        // Web pages can report the nodes below the visible part as visible: check the bounds too.
        return !bounds.isEmpty && Rect.intersects(bounds, screen) && bounds.centerY() in screen.top until screen.bottom
    }

    private fun AccessibilityService.screenBounds(): Rect {
        val metrics = resources.displayMetrics
        val fromWindows = try {
            windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.map { Rect().also(it::getBoundsInScreen) }
                ?.maxByOrNull { it.width() * it.height() }
        } catch (_: Exception) {
            null
        }
        return fromWindows?.takeUnless { it.isEmpty } ?: Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun AccessibilityNodeInfo.findAll(ownPackage: String, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(this to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (node.packageName?.toString() == ownPackage) continue
            if (predicate(node)) result.add(node)
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        return result
    }

    private fun AccessibilityService.candidateRoots(): List<AccessibilityNodeInfo> = buildList {
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() != packageName) add(root)
        }
        try {
            windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.mapNotNull { it.root }
                ?.filter { root -> root.packageName?.toString() != packageName && none { it == root } }
                ?.let(::addAll)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't list windows", ex)
        }
    }

    private fun AccessibilityNodeInfo.findMatch(ownPackage: String, kind: ElementSelectorKind, query: String, screen: Rect): AccessibilityNodeInfo? {
        // Fast path using the framework lookups, then fall back on a full tree walk for exact matching rules.
        val fastCandidates: List<AccessibilityNodeInfo> = when (kind) {
            ElementSelectorKind.RESOURCE_ID -> {
                val fullId = if (query.contains(":id/")) query else "${packageName}:id/$query"
                findAccessibilityNodeInfosByViewId(fullId).orEmpty()
            }
            ElementSelectorKind.TEXT_EXACT,
            ElementSelectorKind.TEXT_CONTAINS -> findAccessibilityNodeInfosByText(query).orEmpty()
            ElementSelectorKind.CONTENT_DESCRIPTION -> emptyList()
        }
        fastCandidates.firstOrNull { it.isOnScreen(screen) && it.matches(kind, query) }?.let { return it }

        // Elements out of the screen are kept as a fallback, the caller scrolls to them.
        var hidden: AccessibilityNodeInfo? = fastCandidates.firstOrNull { it.matches(kind, query) }
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(this to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (node.packageName?.toString() == ownPackage) continue
            if (node.matches(kind, query)) {
                if (node.isOnScreen(screen)) return node
                if (hidden == null) hidden = node
            }
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it to depth + 1) }
            }
        }
        return hidden
    }

    private fun AccessibilityNodeInfo.matches(kind: ElementSelectorKind, query: String): Boolean = when (kind) {
        ElementSelectorKind.TEXT_EXACT ->
            text?.toString()?.trim()?.equals(query, ignoreCase = true) == true ||
                contentDescription?.toString()?.trim()?.equals(query, ignoreCase = true) == true
        ElementSelectorKind.TEXT_CONTAINS ->
            text?.toString()?.contains(query, ignoreCase = true) == true
        ElementSelectorKind.RESOURCE_ID -> viewIdResourceName?.let { id ->
            id == query || id.substringAfter(":id/") == query
        } == true
        ElementSelectorKind.CONTENT_DESCRIPTION ->
            contentDescription?.toString()?.contains(query, ignoreCase = true) == true
    }
}

private const val MAX_PARENT_DEPTH = 6
private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
private val SYSTEM_PACKAGE_PARTS = listOf("samsungpass", "biometrics", "authfw")
private const val MAX_TREE_DEPTH = 60
private const val TAG = "ElementTapExecutor"
