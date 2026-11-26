package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline.Block
import com.kalendrys.app.domain.DayTimeline.BlockEnd
import com.kalendrys.app.domain.DayTimeline.TaskItem
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Casos extremos da tarefa aninhada (v1.10.0, ADR-0002 16.10.8), além do [DayTimelineNestingTest]:
 * várias tarefas no mesmo minuto, dois blocos que começam juntos, rotina curta dentro de outra (N1) com
 * tarefa no início da curta, bloco que atravessa a meia-noite, bloco remarcado, tarefa feita e
 * sobreposição parcial. Lógica pura.
 */
class DayTimelineNestingExtrasTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 10, 24)
    private val createdAt = day.minusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun minute(hhmm: String) = hhmm.split(":").map { it.toInt() }.let { (h, m) -> h * 60 + m }

    private fun routine(id: Long, start: String, minutes: Int) =
        Routine(id = id, title = "R$id", startMinute = minute(start), durationMinutes = minutes, createdAt = createdAt)

    private fun task(id: Long, time: String): Task {
        val (h, m) = time.split(":").map(String::toInt)
        return Task(
            id = id,
            title = "T$id",
            dueDate = day.toEpochDay(),
            reminderAt = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli(),
            createdAt = TEST_CREATED_AT,
        )
    }

    private fun build(
        routines: List<Routine>,
        tasks: List<Task>,
        overrides: Map<Long, List<RoutineOverride>> = emptyMap(),
        doneTaskIds: Set<Long> = emptySet(),
    ) = DayTimeline.build(routines, emptySet(), day, zone, tasks = tasks, doneTaskIds = doneTaskIds, overrides = overrides, includeSkipped = true)

    private fun List<DayTimeline.Item>.task(id: Long) = filterIsInstance<TaskItem>().single { it.task.id == id }

    @Test
    fun `varias tarefas no mesmo minuto dentro - todas aninhadas e um fim so, depois da ultima`() {
        val bloco = routine(1, "07:00", 60)
        val items = build(listOf(bloco), (10L..13L).map { task(it, "07:20") })

        assertTrue((10L..13L).all { items.task(it).container == bloco })
        assertEquals(4, items.filterIsInstance<Block>().single().nestedTasks)
        assertEquals(1, items.count { it is BlockEnd })
        val keys = items.map { it.key }
        assertEquals("o fim vem logo depois da última de dentro", keys.indexOf("routine_end_1") - 1, keys.indexOfLast { it.startsWith("task_") })
    }

    @Test
    fun `dois blocos que comecam no mesmo minuto - fica o que vem depois na trilha`() {
        val a = routine(1, "07:00", 60)
        val b = routine(2, "07:00", 30)
        val items = build(listOf(a, b), listOf(task(10, "07:10"), task(11, "07:45")))
        val blocks = items.filterIsInstance<Block>().map { it.routine }
        val later = blocks.last()

        assertEquals("empate de início: o último na trilha", later, items.task(10).container)
        // 07:45 só cabe no de 60 min.
        assertEquals(a, items.task(11).container)
    }

    @Test
    fun `N1 - rotina curta dentro de outra, tarefa no inicio da curta vai para a curta e no fim dela volta para a longa`() {
        val longa = routine(1, "06:00", 180)
        val curta = routine(2, "07:00", 15)
        val items = build(listOf(longa, curta), listOf(task(10, "07:00"), task(11, "07:15"), task(12, "06:59")))

        assertEquals(curta, items.task(10).container)
        assertEquals("no fim da curta não conta: volta para a longa", longa, items.task(11).container)
        assertEquals(longa, items.task(12).container)
        assertEquals("longa com 2, curta com 1", listOf(2, 1), items.filterIsInstance<Block>().sortedBy { it.routine.id }.map { it.nestedTasks })
    }

    @Test
    fun `bloco que atravessa a meia-noite contem a tarefa das 23h59 e o fim le 00h30`() {
        val noite = routine(1, "23:30", 60)
        val items = build(listOf(noite), listOf(task(10, "23:59"), task(11, "23:00")))

        assertEquals(noite, items.task(10).container)
        assertNull(items.task(11).container)
        val end = items.filterIsInstance<BlockEnd>().single()
        assertEquals(minute("23:30") + 60, end.startMinute)
        assertEquals("00:30", minuteOfDayLabel(end.startMinute))
        assertEquals("o fim é o último item", end, items.last())
    }

    @Test
    fun `tarefa a meia-noite em ponto fica fora de bloco que comeca depois`() {
        val items = build(listOf(routine(1, "00:01", 30)), listOf(task(10, "00:00")))
        assertNull(items.task(10).container)
        assertTrue(items.none { it is BlockEnd })
    }

    @Test
    fun `bloco remarcado no dia contem pela hora nova, nao pela antiga`() {
        val bloco = routine(1, "07:00", 60)
        val moved = RoutineOverride(
            routineId = 1,
            epochDay = day.toEpochDay(),
            type = RoutineOverrideType.RESCHEDULE,
            newStartMinute = minute("09:00"),
            createdAt = TEST_CREATED_AT,
        )
        val items = build(listOf(bloco), listOf(task(10, "07:30"), task(11, "09:30")), mapOf(1L to listOf(moved)))

        assertNull("07:30 era dentro da hora antiga", items.task(10).container)
        assertEquals(bloco.id, items.task(11).container?.id)
    }

    @Test
    fun `tarefa feita dentro do bloco continua aninhada e feita`() {
        val bloco = routine(1, "07:00", 60)
        val items = build(listOf(bloco), listOf(task(10, "07:30")), doneTaskIds = setOf(10L))
        assertEquals(bloco, items.task(10).container)
        assertTrue(items.task(10).done)
    }

    /**
     * Sobreposição PARCIAL: cada tarefa vai para o bloco certo. A ORDEM da coluna não é conferida aqui:
     * hoje o fim de A (08:00) sai logo depois da última tarefa de A (07:15), ANTES do início de B
     * (07:30), e a coluna lê 07:00, 07:15, 08:00, 07:30, 07:45, 08:30, 09:00. Defeito reportado pelo
     * Lince na T3; quando a regra de posição do fim for decidida, este teste ganha a ordem.
     */
    @Test
    fun `sobreposicao parcial - cada tarefa no seu bloco`() {
        // A 07:00–08:00 e B 07:30–09:00: 07:15 só em A; 07:45 em A e B (fica B, que começou depois).
        val a = routine(1, "07:00", 60)
        val b = routine(2, "07:30", 90)
        val items = build(listOf(a, b), listOf(task(10, "07:15"), task(11, "07:45"), task(12, "08:30")))

        assertEquals(a, items.task(10).container)
        assertEquals(b, items.task(11).container)
        assertEquals(b, items.task(12).container)
        assertEquals(2, items.count { it is BlockEnd })
    }
}
