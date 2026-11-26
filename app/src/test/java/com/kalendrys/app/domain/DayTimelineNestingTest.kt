package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline.Block
import com.kalendrys.app.domain.DayTimeline.BlockEnd
import com.kalendrys.app.domain.DayTimeline.TaskItem
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tarefa aninhada no bloco (v1.10.0, ADR-0002 16.10.8, P47-P50): quem está dentro, a ordem da trilha
 * e que as contagens não mudam. Lógica pura.
 */
class DayTimelineNestingTest {

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

    private fun build(routines: List<Routine>, tasks: List<Task>, overrides: Map<Long, List<RoutineOverride>> = emptyMap()) =
        DayTimeline.build(routines, emptySet(), day, zone, tasks = tasks, overrides = overrides, includeSkipped = true)

    private fun List<DayTimeline.Item>.task(id: Long) = filterIsInstance<TaskItem>().single { it.task.id == id }

    @Test
    fun `inicio dentro conta e fim fora nao - a coluna le 07h00, 07h00, 07h30, 08h00`() {
        val treino = routine(1, "07:00", 60)
        val items = build(listOf(treino), listOf(task(10, "07:00"), task(11, "07:30"), task(12, "08:00")))

        assertEquals(treino, items.task(10).container)
        assertEquals(treino, items.task(11).container)
        assertNull("às 08:00 o bloco já acabou", items.task(12).container)
        assertEquals(2, items.filterIsInstance<Block>().single().nestedTasks)
        // Ordem: bloco, as de dentro, o fim do bloco, e a de fora.
        assertEquals(
            listOf("routine_1", "task_10", "task_11", "routine_end_1", "task_12"),
            items.map { it.key },
        )
        assertEquals(listOf(minute("07:00"), minute("07:00"), minute("07:30"), minute("08:00")), items.take(4).map { it.startMinute })
    }

    @Test
    fun `sobrepostos - fica com o bloco que comecou por ultimo`() {
        val longo = routine(1, "07:00", 120)
        val curto = routine(2, "08:00", 30)
        val items = build(listOf(longo, curto), listOf(task(10, "08:15"), task(11, "07:30"), task(12, "08:45")))

        assertEquals(curto, items.task(10).container)
        assertEquals(longo, items.task(11).container)
        assertEquals("depois do curto, volta ao longo", longo, items.task(12).container)
    }

    @Test
    fun `bloco pulado e bloco sem duracao nao contem nada`() {
        val pulado = routine(1, "07:00", 60)
        val marco = routine(2, "09:00", 0)
        val skip = RoutineOverride(routineId = 1, epochDay = day.toEpochDay(), type = RoutineOverrideType.SKIP, createdAt = TEST_CREATED_AT)
        val items = build(listOf(pulado, marco), listOf(task(10, "07:30"), task(11, "09:00")), mapOf(1L to listOf(skip)))

        assertNull(items.task(10).container)
        assertNull(items.task(11).container)
        assertTrue(items.none { it is BlockEnd })
        assertTrue(items.filterIsInstance<Block>().all { it.nestedTasks == 0 })
    }

    @Test
    fun `contagens nao mudam - mesmas tarefas e blocos, e o fim do bloco nao conta`() {
        val items = build(listOf(routine(1, "07:00", 60)), listOf(task(10, "07:15"), task(11, "10:00")))

        assertEquals(2, items.filterIsInstance<TaskItem>().size)
        assertEquals(1, items.filterIsInstance<Block>().size)
        assertFalse(items.single { it is BlockEnd }.isActive)
        assertEquals("ativos: bloco e duas tarefas (intervalo conta como antes)", 3, items.count { it.isActive && it !is DayTimeline.Gap })
    }

    @Test
    fun `sem tarefa dentro - trilha igual a de antes`() {
        val items = build(listOf(routine(1, "07:00", 60)), listOf(task(10, "09:00")))

        assertTrue(items.none { it is BlockEnd })
        assertEquals(0, items.filterIsInstance<Block>().single().nestedTasks)
        assertNull(items.task(10).container)
    }
}
