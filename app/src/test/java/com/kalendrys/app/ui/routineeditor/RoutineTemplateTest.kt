package com.kalendrys.app.ui.routineeditor

import com.kalendrys.app.domain.RoutineKind
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
    fun `Treino nasce com o tipo Exercicio e os outros com Nenhum`() {
        // v1.10.0 (ADR-0002 16.10.5): o tipo vem do modelo; exercícios não.
        assertEquals(RoutineKind.EXERCISE, RoutineTemplate.WORKOUT.toDraft("Treino").kind)
        RoutineTemplate.entries.filter { it != RoutineTemplate.WORKOUT }.forEach { template ->
            assertEquals(template.name, RoutineKind.NONE, template.toDraft("X").kind)
        }
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
