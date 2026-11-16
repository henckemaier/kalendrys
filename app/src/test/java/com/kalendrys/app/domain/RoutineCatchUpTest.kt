package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plano do catch-up do aviso de bloco (ADR-0003, 4.3.1 e 4.3.4). */
class RoutineCatchUpTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val day: LocalDate = LocalDate.of(2030, 1, 7) // segunda

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun block(id: Long, startMinute: Int, durationMinutes: Int = 0, remind: Boolean = true) =
        Routine(id = id, title = "Bloco $id", startMinute = startMinute, durationMinutes = durationMinutes, remind = remind, createdAt = 0L)

    private fun plan(
        routines: List<Routine>,
        lastSeen: Long,
        now: Long,
        overrides: Map<Long, List<RoutineOverride>> = emptyMap(),
        doneByDay: Map<LocalDate, Set<Long>> = emptyMap(),
    ) = RoutineCatchUp.plan(routines, overrides, doneByDay, lastSeen, now, zone)

    @Test
    fun `bloco atrasado dentro da tolerancia e avisado`() {
        val due = plan(listOf(block(1, 9 * 60)), lastSeen = at(day, 8, 50), now = at(day, 9, 3))

        assertEquals(listOf(RoutineCatchUp.Due(day, listOf(block(1, 9 * 60)))), due)
    }

    @Test
    fun `limite exato da tolerancia de 60 minutos ainda avisa, um minuto a mais descarta`() {
        val routines = listOf(block(1, 9 * 60))

        assertEquals(1, plan(routines, lastSeen = at(day, 8, 0), now = at(day, 10, 0)).size)
        assertTrue(plan(routines, lastSeen = at(day, 8, 0), now = at(day, 10, 1)).isEmpty())
    }

    @Test
    fun `limites da janela - inicio igual a marca fica de fora, inicio igual a agora entra`() {
        val routines = listOf(block(1, 9 * 60))

        assertTrue("(marca, agora] é aberta embaixo", plan(routines, lastSeen = at(day, 9, 0), now = at(day, 9, 5)).isEmpty())
        assertEquals(1, plan(routines, lastSeen = at(day, 8, 0), now = at(day, 9, 0)).size)
    }

    @Test
    fun `bloco futuro nao e avisado`() {
        assertTrue(plan(listOf(block(1, 10 * 60)), lastSeen = at(day, 8, 0), now = at(day, 9, 59)).isEmpty())
    }

    @Test
    fun `bloco com duracao ja terminado nao e avisado mesmo dentro da tolerancia`() {
        val routines = listOf(block(1, 9 * 60, durationMinutes = 30))

        assertEquals("ainda em andamento às 9:29", 1, plan(routines, lastSeen = at(day, 8, 0), now = at(day, 9, 29)).size)
        assertTrue("terminou às 9:30", plan(routines, lastSeen = at(day, 8, 0), now = at(day, 9, 30)).isEmpty())
    }

    @Test
    fun `bloco sem duracao segue so a regra dos 60 minutos`() {
        assertEquals(1, plan(listOf(block(1, 9 * 60)), lastSeen = at(day, 8, 0), now = at(day, 9, 45)).size)
    }

    @Test
    fun `bloco concluido no dia nao e avisado`() {
        val due = plan(listOf(block(1, 9 * 60)), at(day, 8, 0), at(day, 9, 3), doneByDay = mapOf(day to setOf(1L)))

        assertTrue(due.isEmpty())
    }

    @Test
    fun `aviso desligado nao e avisado`() {
        assertTrue(plan(listOf(block(1, 9 * 60, remind = false)), at(day, 8, 0), at(day, 9, 3)).isEmpty())
    }

    @Test
    fun `ocorrencia pulada nao e avisada`() {
        val skip = RoutineOverride(routineId = 1, epochDay = day.toEpochDay(), type = RoutineOverrideType.SKIP)

        assertTrue(plan(listOf(block(1, 9 * 60)), at(day, 8, 0), at(day, 9, 3), overrides = mapOf(1L to listOf(skip))).isEmpty())
    }

    @Test
    fun `remarcacao de minuto usa o horario efetivo`() {
        val moved = RoutineOverride(
            routineId = 1,
            epochDay = day.toEpochDay(),
            type = RoutineOverrideType.RESCHEDULE,
            newStartMinute = 11 * 60,
        )
        val overrides = mapOf(1L to listOf(moved))
        val routines = listOf(block(1, 9 * 60))

        assertTrue("o horário base (9:00) não vale mais", plan(routines, at(day, 8, 0), at(day, 9, 3), overrides).isEmpty())
        assertEquals(1, plan(routines, at(day, 10, 0), at(day, 11, 3), overrides).size)
    }

    @Test
    fun `remarcacao para outro dia tira do dia de origem e avisa no de destino`() {
        val tomorrow = day.plusDays(1)
        val moved = RoutineOverride(
            routineId = 1,
            epochDay = day.toEpochDay(),
            type = RoutineOverrideType.RESCHEDULE,
            newEpochDay = tomorrow.toEpochDay(),
            newStartMinute = 8 * 60,
        )
        // Só um bloco que ocorre na segunda: na terça, só a ocorrência remarcada.
        val routines = listOf(block(1, 9 * 60).copy(daysOfWeek = RoutineDays.bit(DayOfWeek.MONDAY)))
        val overrides = mapOf(1L to listOf(moved))

        assertTrue(plan(routines, at(day, 8, 0), at(day, 9, 3), overrides).isEmpty())
        assertEquals(
            listOf(RoutineCatchUp.Due(tomorrow, routines)),
            plan(routines, at(tomorrow, 7, 0), at(tomorrow, 8, 3), overrides),
        )
    }

    @Test
    fun `janela cruzando a meia-noite avalia ontem e hoje`() {
        val late = block(1, 23 * 60 + 30) // 23:30 de ontem
        val early = block(2, 5) // 00:05 de hoje
        val tomorrow = day.plusDays(1)

        val due = plan(listOf(late, early), lastSeen = at(day, 23, 0), now = at(tomorrow, 0, 10))

        assertEquals(
            listOf(RoutineCatchUp.Due(day, listOf(late)), RoutineCatchUp.Due(tomorrow, listOf(early))),
            due,
        )
        assertEquals(listOf(day, tomorrow), RoutineCatchUp.days(at(day, 23, 0), at(tomorrow, 0, 10), zone))
    }

    @Test
    fun `bloco com duracao cruzando a meia-noite avisa no dia de inicio ate terminar`() {
        val late = block(1, 23 * 60 + 30, durationMinutes = 60) // 23:30 de ontem até 00:30 de hoje
        val tomorrow = day.plusDays(1)

        assertEquals(
            "às 00:10 ainda está em andamento: avisa no dia de início",
            listOf(RoutineCatchUp.Due(day, listOf(late))),
            plan(listOf(late), lastSeen = at(day, 23, 0), now = at(tomorrow, 0, 10)),
        )
        assertTrue("às 00:30 já terminou", plan(listOf(late), lastSeen = at(day, 23, 0), now = at(tomorrow, 0, 30)).isEmpty())
    }

    @Test
    fun `dias da janela respeitam a tolerancia, nao a marca antiga`() {
        // Marca de três dias atrás: só interessa a última hora.
        assertEquals(listOf(day), RoutineCatchUp.days(at(day.minusDays(3), 9, 0), at(day, 9, 3), zone))
    }

    @Test
    fun `relogio voltou - janela vazia`() {
        assertTrue(plan(listOf(block(1, 9 * 60)), lastSeen = at(day, 9, 10), now = at(day, 9, 3)).isEmpty())
        assertTrue(RoutineCatchUp.days(at(day, 9, 10), at(day, 9, 3), zone).isEmpty())
    }

    @Test
    fun `varios blocos no dia saem em ordem de inicio`() {
        val first = block(2, 9 * 60)
        val second = block(1, 9 * 60 + 10)

        val due = plan(listOf(second, first), lastSeen = at(day, 8, 0), now = at(day, 9, 20))

        assertEquals(listOf(first, second), due.single().routines)
    }

    @Test
    fun `isDiscarded - sem aviso ou concluida no dia e descartada`() {
        assertTrue(RoutineCatchUp.isDiscarded(block(1, 9 * 60, remind = false), emptySet()))
        assertTrue(RoutineCatchUp.isDiscarded(block(1, 9 * 60), setOf(1L)))
        assertEquals(false, RoutineCatchUp.isDiscarded(block(1, 9 * 60), setOf(2L)))
    }
}
