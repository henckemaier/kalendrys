package com.kalendrys.app.reminder

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.RoutineOverrideType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Texto do aviso de rotina ([RoutineNotifications.timeLabel]): o horário mostrado é o EFETIVO da
 * ocorrência do dia (honra o RESCHEDULE), não o horário-base da rotina — corrige o A1 do T36.
 */
class RoutineNotificationsTest {

    private val date = LocalDate.of(2026, 11, 10)

    private fun treino() = Routine(id = 1, title = "Treino", startMinute = 18 * 60, durationMinutes = 30)

    private fun reschedule(newStartMinute: Int?, newEpochDay: Long? = null) = RoutineOverride(
        routineId = 1,
        epochDay = date.toEpochDay(),
        type = RoutineOverrideType.RESCHEDULE,
        newStartMinute = newStartMinute,
        newEpochDay = newEpochDay,
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
        val marco = Routine(id = 1, title = "Remédio", startMinute = 8 * 60, durationMinutes = 0)
        assertEquals(
            "09:00",
            RoutineNotifications.timeLabel(marco, date, listOf(reschedule(newStartMinute = 9 * 60))),
        )
    }
}
