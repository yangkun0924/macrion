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
    /** The element is not on the screen. */
    data object NotFound : ElementLookup()
}

@Singleton
internal class ElementTapExecutor @Inject constructor() {

    /** Look for the element once in the current windows, and click it if possible. Must be called on main thread. */
    fun findAndClick(service: AccessibilityService, kind: ElementSelectorKind, value: String): ElementLookup {
        val query = value.trim()
        if (query.isEmpty()) return ElementLookup.NotFound

        for (root in service.candidateRoots()) {
            val node = root.findMatch(service.packageName, kind, query) ?: continue

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

        return ElementLookup.NotFound
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

    private fun AccessibilityNodeInfo.findMatch(ownPackage: String, kind: ElementSelectorKind, query: String): AccessibilityNodeInfo? {
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
        fastCandidates.firstOrNull { it.isVisibleToUser && it.matches(kind, query) }?.let { return it }

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(this to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (node.packageName?.toString() == ownPackage) continue
            if (node.isVisibleToUser && node.matches(kind, query)) return node
            if (depth >= MAX_TREE_DEPTH) continue
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it to depth + 1) }
            }
        }
        return null
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
private const val MAX_TREE_DEPTH = 60
private const val TAG = "ElementTapExecutor"
