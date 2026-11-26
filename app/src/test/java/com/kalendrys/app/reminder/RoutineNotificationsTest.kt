package com.kalendrys.app.reminder

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Texto do aviso de rotina ([RoutineNotifications.timeLabel]): o horário mostrado é o EFETIVO da
 * ocorrência do dia (honra o RESCHEDULE), não o horário-base da rotina — corrige o A1 do T36.
 */
class RoutineNotificationsTest {

    private val date = LocalDate.of(2026, 11, 10)

    private fun treino() = Routine(id = 1, title = "Treino", startMinute = 18 * 60, durationMinutes = 30, createdAt = TEST_CREATED_AT)

    private fun reschedule(newStartMinute: Int?, newEpochDay: Long? = null) = RoutineOverride(
        routineId = 1,
        epochDay = date.toEpochDay(),
        type = RoutineOverrideType.RESCHEDULE,
        newStartMinute = newStartMinute,
        newEpochDay = newEpochDay,
        createdAt = TEST_CREATED_AT,
    )

    @Test
    fun `sem excecao usa o horario base da rotina`() {
        assertEquals("18:00–18:30 · 30 min", RoutineNotifications.timeLabel(treino(), date))
    }

    @Test
    fun `honra o RESCHEDULE do minuto no proprio dia`() {
        // Base 18:00, remarcada para 20:00 -> o texto segue a ocorrência (20:00–20:30), não a base.
        assertEquals(
            "20:00–20:30 · 30 min",
            RoutineNotifications.timeLabel(treino(), date, listOf(reschedule(newStartMinute = 20 * 60))),
        )
    }

    @Test
    fun `marco sem duracao mostra so o horario efetivo`() {
        val marco = Routine(id = 1, title = "Remédio", startMinute = 8 * 60, durationMinutes = 0, createdAt = TEST_CREATED_AT)
        assertEquals(
            "09:00",
            RoutineNotifications.timeLabel(marco, date, listOf(reschedule(newStartMinute = 9 * 60))),
        )
    }

    // --- Ações do aviso de bloco: sem "Abrir lista" desde a v1.10.0 (rotina não tem lista) ---

    @Test
    fun `aviso de bloco tem Concluir, Pular e Adiar`() {
        assertEquals(
            listOf(RoutineNotifications.Action.COMPLETE, RoutineNotifications.Action.SKIP, RoutineNotifications.Action.SNOOZE),
            RoutineNotifications.ACTIONS,
        )
    }
}
