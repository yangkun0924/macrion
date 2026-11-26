/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.smart.config.data

import io.github.vibhor1102.macrion.core.base.identifier.Identifier
import io.github.vibhor1102.macrion.core.domain.model.action.Action
import io.github.vibhor1102.macrion.core.domain.model.action.Pause
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** A new action goes right after the action the user looked at last, not always at the end. */
class ActionInsertPositionTest {
    private val eventId = Identifier(databaseId = 1)
    private fun pause(id: Long, name: String = "P$id") = Pause(Identifier(databaseId = id), eventId, name, 0, 100L)
    private fun newPause(tempId: Long, name: String) = Pause(Identifier(tempId = tempId), eventId, name, 0, 100L)
    private fun editor(actions: List<Action>) =
        ActionsEditor<Any>({}, MutableStateFlow(null)).apply { startEdition(actions) }
    private fun names(editor: ActionsEditor<Any>) = editor.editedList.value!!.map { it.name }
    private fun add(editor: ActionsEditor<Any>, action: Action) {
        editor.startItemEdition(action)
        editor.upsertEditedItem()
    }

    @Test fun withoutLookingAtAnActionNewOnesGoAtTheEnd() {
        val editor = editor(listOf(pause(1), pause(2), pause(3)))
        add(editor, newPause(10, "New"))
        assertEquals(listOf("P1", "P2", "P3", "New"), names(editor))
    }

    @Test fun newActionGoesRightAfterTheOneLookedAt() {
        val editor = editor(listOf(pause(1), pause(2), pause(3)))
        editor.startItemEdition(pause(2))
        editor.stopItemEdition() // Opened then closed without changes.
        add(editor, newPause(10, "New"))
        assertEquals(listOf("P1", "P2", "New", "P3"), names(editor))
        assertEquals(listOf(0, 1, 2, 3), editor.editedList.value!!.map { it.priority })
    }

    @Test fun severalNewActionsFollowEachOther() {
        val editor = editor(listOf(pause(1), pause(2), pause(3)))
        editor.startItemEdition(pause(1))
        editor.upsertEditedItem()
        add(editor, newPause(10, "A"))
        add(editor, newPause(11, "B"))
        assertEquals(listOf("P1", "A", "B", "P2", "P3"), names(editor))
    }

    @Test fun editingAnActionKeepsItsPlace() {
        val editor = editor(listOf(pause(1), pause(2), pause(3)))
        editor.startItemEdition(pause(3))
        editor.updateEditedItem(pause(3, "Renamed"))
        editor.upsertEditedItem()
        editor.startItemEdition(pause(1))
        editor.updateEditedItem(pause(1, "First"))
        editor.upsertEditedItem()
        assertEquals(listOf("First", "P2", "Renamed"), names(editor))
    }
}
