package com.kalendrys.app.ui.routineeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTemplateTest {

    @Test
    fun `toDraft aplica o titulo e os campos do modelo`() {
        val draft = RoutineTemplate.WORKOUT.toDraft("Treino")
        assertEquals("Treino", draft.title)
        assertEquals(RoutineTemplate.WORKOUT.icon, draft.icon)
        assertEquals(RoutineTemplate.WORKOUT.color, draft.color)
        assertEquals(RoutineTemplate.WORKOUT.startMinute, draft.startMinute)
        assertEquals(RoutineTemplate.WORKOUT.durationMinutes, draft.durationMinutes)
    }

    @Test
    fun `todo modelo com titulo gera um rascunho valido`() {
        RoutineTemplate.entries.forEach { template ->
            val draft = template.toDraft("X")
            assertTrue("modelo ${template.name} deveria ser válido", draft.isValid)
            assertEquals(template.icon, draft.icon)
            assertEquals(template.startMinute, draft.startMinute)
        }
    }
}
