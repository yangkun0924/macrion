/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.externallaunch.shortcut

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat

import io.github.vibhor1102.macrion.feature.externallaunch.R
import io.github.vibhor1102.macrion.feature.externallaunch.repeat.ScenarioRepeatRunner

/**
 * Home screen shortcuts starting a scenario with a single tap.
 *
 * Each shortcut gets its own color, picked among the ones not used yet by the other shortcuts on the home screen, so
 * several shortcuts are told apart at a glance.
 */
object ScenarioShortcuts {

    /** Colors of the shortcut icons, far enough from each other to be told apart. */
    val PALETTE: IntArray = intArrayOf(
        0xFF3D5AFE.toInt(), // blue
        0xFFFF6D00.toInt(), // orange
        0xFF00A86B.toInt(), // green
        0xFFE53935.toInt(), // red
        0xFF8E24AA.toInt(), // purple
        0xFF00ACC1.toInt(), // teal
        0xFFF9A825.toInt(), // yellow
        0xFFD81B60.toInt(), // pink
        0xFF6D4C41.toInt(), // brown
        0xFF546E7A.toInt(), // grey blue
    )

    /**
     * Ask the launcher to pin a shortcut starting the given scenario.
     * The user confirms the placement in the launcher dialog.
     *
     * A shortcut already on the home screen for this scenario is renamed first: launchers keep the label of the first
     * pin otherwise, the name given to the scenario afterwards would never show.
     *
     * @param repeatTimes null for a single run, the number of runs (or [ScenarioRepeatRunner.INFINITE]) for a shortcut
     * repeating the scenario.
     */
    fun requestPinSmartScenario(
        context: Context,
        scenarioId: Long,
        scenarioName: String,
        isDumb: Boolean = false,
        repeatTimes: Int? = null,
    ) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.toast_shortcut_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        val id = shortcutId(scenarioId, isDumb, repeatTimes)
        val pinned = pinnedIds(context)
        val color = colorFor(context, id, pinned)
        val shortcut = buildShortcut(context, id, scenarioId, scenarioName, isDumb, repeatTimes, color)
        if (id in pinned) {
            update(context, listOf(shortcut))
            Toast.makeText(context, R.string.toast_shortcut_renamed, Toast.LENGTH_LONG).show()
        }
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * Give the shortcuts on the home screen the current names of their scenarios (a scenario renamed in the editor
     * keeps its shortcut). Scenarios are given as (id, name, isDumb).
     */
    fun syncNames(context: Context, scenarios: List<Triple<Long, String, Boolean>>) {
        // The home screen shortcuts and the app icon menu.
        val pinned = try {
            ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Can't read the pinned shortcuts", ex)
            return
        }
        if (pinned.isEmpty()) return
        val labels = pinned.associate { it.id to it.shortLabel.toString() }
        val pinnedIds = labels.keys
        val changed = scenarios.flatMap { (id, name, isDumb) ->
            val base = shortcutId(id, isDumb, null)
            labels.keys.filter { it == base || it.startsWith("$base$REPEAT_SUFFIX") }.mapNotNull { shortcutId ->
                val repeat = if (shortcutId == base) null else shortcutId.removePrefix("$base$REPEAT_SUFFIX").toIntOrNull()
                // A shortcut pinned before the colors existed gets its own color now.
                val hadColor = hasColor(context, shortcutId)
                val shortcut = buildShortcut(context, shortcutId, id, name, isDumb, repeat, colorFor(context, shortcutId, pinnedIds))
                shortcut.takeIf { !hadColor || labels[shortcutId] != it.shortLabel.toString() }
            }
        }
        if (changed.isNotEmpty()) update(context, changed)
    }

    /**
     * Let the user choose where the play button goes: the home screen, the menu of the TouchTouch icon (long press it
     * in the apps screen or on the home screen), or both.
     * @param onDone called once the choice is made or the dialog closed.
     */
    fun chooseWhereToAdd(
        context: Context,
        scenarioId: Long,
        scenarioName: String,
        isDumb: Boolean = false,
        repeatTimes: Int? = null,
        onDone: () -> Unit = {},
    ) {
        val id = shortcutId(scenarioId, isDumb, repeatTimes)
        val inMenu = id in menuIds(context)
        val items = buildList {
            add(context.getString(R.string.item_add_where_home))
            add(context.getString(R.string.item_add_where_app_menu))
            add(context.getString(R.string.item_add_where_both))
            if (inMenu) add(context.getString(R.string.item_add_where_remove_menu))
        }
        val label = if (repeatTimes == null) scenarioName else repeatLabel(context, scenarioName, repeatTimes)
        var done = false
        val finish = { if (!done) { done = true; onDone() } }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_title_add_where, label))
            .setItems(items.toTypedArray()) { _, index ->
                // First let the caller show its screen: the launcher's "add to home screen" dialog must come on top.
                finish()
                // The caller's activity may be finishing now.
                val app = context.applicationContext
                when (index) {
                    0 -> requestPinSmartScenario(app, scenarioId, scenarioName, isDumb, repeatTimes)
                    1 -> addToAppIconMenu(app, scenarioId, scenarioName, isDumb, repeatTimes)
                    2 -> {
                        addToAppIconMenu(app, scenarioId, scenarioName, isDumb, repeatTimes)
                        requestPinSmartScenario(app, scenarioId, scenarioName, isDumb, repeatTimes)
                    }
                    3 -> removeFromAppIconMenu(app, id)
                }
            }
            .setNegativeButton(R.string.button_add_where_cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    /** The menu shown when the TouchTouch icon is long pressed: newest first, launchers show only a few. */
    private fun addToAppIconMenu(context: Context, scenarioId: Long, scenarioName: String, isDumb: Boolean, repeatTimes: Int?) {
        val id = shortcutId(scenarioId, isDumb, repeatTimes)
        val max = maxMenuEntries(context)
        val ids = (listOf(id) + menuIds(context).filter { it != id })
        val kept = ids.take(max)
        val dropped = ids.drop(max)
        saveMenuIds(context, kept)

        val shortcut = ShortcutInfoCompat.Builder(
            buildShortcut(context, id, scenarioId, scenarioName, isDumb, repeatTimes, colorFor(context, id, pinnedIds(context)))
        ).setRank(0).build()
        try {
            if (dropped.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, dropped)
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            Log.i(TAG, "Added to the app icon menu: $id, menu $kept")
        } catch (ex: Exception) {
            Log.w(TAG, "Can't add to the app icon menu", ex)
            return
        }
        val message =
            if (dropped.isEmpty()) context.getString(R.string.toast_added_app_menu)
            else context.getString(R.string.toast_added_app_menu_full, max)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun removeFromAppIconMenu(context: Context, id: String) {
        saveMenuIds(context, menuIds(context).filter { it != id })
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
        } catch (ex: Exception) {
            Log.w(TAG, "Can't remove from the app icon menu", ex)
        }
        Toast.makeText(context, R.string.toast_removed_app_menu, Toast.LENGTH_SHORT).show()
    }

    /** A scenario was deleted: take it out of the app icon menu. */
    fun onScenarioDeleted(context: Context, scenarioId: Long, isDumb: Boolean) {
        val base = shortcutId(scenarioId, isDumb, null)
        val removed = menuIds(context).filter { it == base || it.startsWith("$base$REPEAT_SUFFIX") }
        if (removed.isEmpty()) return
        saveMenuIds(context, menuIds(context) - removed.toSet())
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, removed)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't remove from the app icon menu", ex)
        }
    }

    private fun maxMenuEntries(context: Context): Int = try {
        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, MENU_MAX_ENTRIES)
    } catch (ex: Exception) {
        MENU_MAX_ENTRIES
    }

    private fun menuIds(context: Context): List<String> =
        context.getSharedPreferences(MENU_PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MENU_IDS, null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private fun saveMenuIds(context: Context, ids: List<String>) {
        context.getSharedPreferences(MENU_PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_MENU_IDS, ids.joinToString(",")) }
    }

    /** The label of a repeating run: "Coffee (3 times)", "Coffee (repeat)". */
    fun repeatLabel(context: Context, name: String, times: Int): String =
        if (times == ScenarioRepeatRunner.INFINITE) context.getString(R.string.shortcut_label_repeat_infinite, name)
        else context.getString(R.string.shortcut_label_repeat_times, name, times)

    private fun buildShortcut(
        context: Context,
        id: String,
        scenarioId: Long,
        scenarioName: String,
        isDumb: Boolean,
        repeatTimes: Int?,
        color: Int,
    ): ShortcutInfoCompat {
        val name = scenarioName.trim().ifBlank { context.getString(R.string.shortcut_default_label) }
        val label = if (repeatTimes == null) name else repeatLabel(context, name, repeatTimes)
        val intent =
            if (repeatTimes == null) ShortcutLauncherActivity.getLaunchIntent(context, scenarioId, isDumb)
            else ShortcutLauncherActivity.getRepeatIntent(context, scenarioId, name, isDumb, repeatTimes)

        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label.take(SHORT_LABEL_MAX_LENGTH))
            .setLongLabel(label.take(LONG_LABEL_MAX_LENGTH))
            .setIcon(IconCompat.createWithAdaptiveBitmap(drawIcon(color, repeatTimes)))
            .setIntent(intent)
            .build()
    }

    /**
     * The color of a shortcut: the one it was given when first pinned, or else the first color of the palette not
     * used by another shortcut on the home screen.
     */
    private fun colorFor(context: Context, id: String, pinnedIds: Set<String>): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(id)) return prefs.getInt(id, PALETTE[0])

        val used = pinnedIds.filter { it != id && prefs.contains(it) }.map { prefs.getInt(it, 0) }
        val color = PALETTE.firstOrNull { it !in used }
            ?: PALETTE.minByOrNull { candidate -> used.count { it == candidate } }!!
        prefs.edit { putInt(id, color) }
        return color
    }

    private fun hasColor(context: Context, id: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(id)

    /**
     * Adaptive icon (108dp, the launcher keeps about the 72dp in the middle): a colored disc with a white play arrow
     * on a pale background of the same color. A repeat shortcut gets a loop badge with its count.
     */
    fun drawIcon(color: Int, repeatTimes: Int? = null): Bitmap {
        val size = ICON_SIZE_PX
        val unit = size / 108f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(ColorUtils.blendARGB(color, Color.WHITE, 0.82f))

        val center = size / 2f
        paint.color = color
        canvas.drawCircle(center, center, DISC_RADIUS_DP * unit, paint)

        // Play arrow, a bit right of the center so it looks centered.
        val arrowHalfHeight = ARROW_HALF_HEIGHT_DP * unit
        val arrowWidth = arrowHalfHeight * 1.6f
        val left = center - arrowWidth * 0.4f
        paint.color = Color.WHITE
        canvas.drawPath(
            Path().apply {
                moveTo(left, center - arrowHalfHeight)
                lineTo(left + arrowWidth, center)
                lineTo(left, center + arrowHalfHeight)
                close()
            },
            paint,
        )

        if (repeatTimes != null) {
            val badgeX = center + DISC_RADIUS_DP * unit * 0.78f
            val badgeY = center + DISC_RADIUS_DP * unit * 0.78f
            val badgeRadius = 9.5f * unit
            paint.color = Color.WHITE
            canvas.drawCircle(badgeX, badgeY, badgeRadius, paint)
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * unit
            canvas.drawCircle(badgeX, badgeY, badgeRadius - 0.8f * unit, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            val text = if (repeatTimes == ScenarioRepeatRunner.INFINITE) "∞" else repeatTimes.coerceAtMost(99).toString()
            paint.textSize = (if (text.length > 1) 9f else 11f) * unit
            val bounds = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(text, badgeX, badgeY - bounds.exactCenterY(), paint)
        }
        return bitmap
    }

    private fun shortcutId(scenarioId: Long, isDumb: Boolean, repeatTimes: Int?): String {
        val base = if (isDumb) "dumb-scenario-$scenarioId" else "smart-scenario-$scenarioId"
        return if (repeatTimes == null) base else "$base$REPEAT_SUFFIX$repeatTimes"
    }

    private fun pinnedIds(context: Context): Set<String> = try {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
    } catch (ex: Exception) {
        emptySet()
    }

    private fun update(context: Context, shortcuts: List<ShortcutInfoCompat>) {
        try {
            ShortcutManagerCompat.updateShortcuts(context, shortcuts)
            Log.i(TAG, "Updated shortcuts: ${shortcuts.map { it.id to it.shortLabel }}")
        } catch (ex: Exception) {
            Log.w(TAG, "Can't update the shortcuts", ex)
        }
    }
}

private const val TAG = "ScenarioShortcuts"
private const val PREFS_NAME = "shortcut_colors"
private const val REPEAT_SUFFIX = "-repeat-"
// Launchers cut long names themselves: keep a name readable under the icon rather than a fixed 10 characters.
private const val SHORT_LABEL_MAX_LENGTH = 25
private const val LONG_LABEL_MAX_LENGTH = 100
private const val ICON_SIZE_PX = 432
private const val MENU_MAX_ENTRIES = 5
private const val MENU_PREFS_NAME = "app_icon_menu"
private const val KEY_MENU_IDS = "ids"
private const val DISC_RADIUS_DP = 21f
private const val ARROW_HALF_HEIGHT_DP = 8.5f
