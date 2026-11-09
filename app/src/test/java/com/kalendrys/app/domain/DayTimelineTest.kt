package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline.Block
import com.kalendrys.app.domain.DayTimeline.Gap
import com.kalendrys.app.domain.DayTimeline.Link
import com.kalendrys.app.domain.DayTimeline.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 10, 24)
    private val createdAt = day.minusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun routine(id: Long, start: String, minutes: Int, days: Int = RoutineDays.ALL): Routine {
        val (h, m) = start.split(":").map { it.toInt() }
        return Routine(
            id = id,
            title = "R$id",
            startMinute = h * 60 + m,
            durationMinutes = minutes,
            daysOfWeek = days,
            createdAt = createdAt,
        )
    }

    private fun build(vararg routines: Routine, done: Set<Long> = emptySet()) =
        DayTimeline.build(routines.toList(), done, day, zone)

    /** Tarefa pontual (sem repetição) no dia [date]; [time] "HH:mm" dá o lembrete, null = sem horário. */
    private fun task(id: Long, time: String?, date: LocalDate = day): Task {
        val reminderAt = time?.let {
            val (h, m) = it.split(":").map(String::toInt)
            date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        }
        return Task(id = id, title = "T$id", dueDate = date.toEpochDay(), reminderAt = reminderAt)
    }

    @Test
    fun `dia sem rotinas fica vazio`() {
        assertTrue(build(routine(1, "07:00", 30, days = RoutineDays.WEEKDAYS)).isEmpty())
    }

    @Test
    fun `ordena por horario e marca as feitas`() {
        val items = build(routine(1, "20:00", 15), routine(2, "07:00", 30), done = setOf(1))
        val blocks = items.filterIsInstance<Block>()
        assertEquals(listOf(2L, 1L), blocks.map { it.routine.id })
        assertEquals(listOf(false, true), blocks.map { it.done })
    }

    @Test
    fun `blocos encostados ligam com linha cheia e intervalos com tracejada`() {
        val items = build(
            routine(1, "17:20", 15), // termina 17:35
            routine(2, "17:35", 30), // encosta; termina 18:05
            routine(3, "18:15", 15), // 10 min livres: tracejado, sem linha de intervalo
        )
        val blocks = items.filterIsInstance<Block>()
        assertEquals(3, items.size)
        assertEquals(listOf(Link.NONE, Link.SOLID, Link.DASHED), blocks.map { it.linkAbove })
        assertEquals(listOf(Link.SOLID, Link.DASHED, Link.NONE), blocks.map { it.linkBelow })
    }

    @Test
    fun `intervalo de 30 min ou mais vira linha de tempo livre`() {
        val items = build(routine(1, "16:00", 60), routine(2, "18:30", 15))
        assertEquals(Gap(startMinute = 17 * 60, minutes = 90), items[1])
    }

    @Test
    fun `bloco dentro de um mais longo conta como encostado`() {
        val items = build(
            routine(1, "08:00", 120), // até 10:00
            routine(2, "08:30", 15), // dentro do anterior
            routine(3, "09:30", 60), // começa antes das 10:00: ainda encosta
        )
        val blocks = items.filterIsInstance<Block>()
        assertEquals(3, items.size)
        assertEquals(listOf(Link.NONE, Link.SOLID, Link.SOLID), blocks.map { it.linkAbove })
    }

    @Test
    fun `bloco sem duracao e bloco que passa da meia-noite`() {
        val items = build(routine(1, "22:00", 0), routine(2, "23:30", 60))
        val blocks = items.filterIsInstance<Block>()
        assertEquals(Gap(startMinute = 22 * 60, minutes = 90), items[1])
        assertEquals(24 * 60 + 30, blocks.last().endMinute)
    }

    @Test
    fun `tarefa com horario entra na trilha ordenada pelo horario`() {
        val items = DayTimeline.build(
            listOf(routine(1, "07:00", 30), routine(2, "12:00", 30)),
            emptySet(),
            day,
            zone,
            tasks = listOf(task(10, "09:00")),
        )
        // A trilha sai sempre em ordem de horário (blocos, intervalos e tarefas misturados).
        assertEquals(items.map { it.startMinute }.sorted(), items.map { it.startMinute })
        val taskItem = items.filterIsInstance<TaskItem>().single()
        assertEquals(10L, taskItem.task.id)
        assertEquals(9 * 60, taskItem.startMinute)
        assertFalse(taskItem.done)
        // A tarefa das 09:00 fica depois do bloco das 07:00 e antes do das 12:00.
        val blocks = items.filterIsInstance<Block>()
        assertEquals(listOf(7 * 60, 12 * 60), blocks.map { it.startMinute })
        assertTrue(items.indexOf(blocks.first()) < items.indexOf(taskItem))
        assertTrue(items.indexOf(taskItem) < items.indexOf(blocks.last()))
    }

    @Test
    fun `tarefa aparece mesmo sem nenhuma rotina e marca a concluida`() {
        val items = DayTimeline.build(
            emptyList(),
            emptySet(),
            day,
            zone,
            tasks = listOf(task(10, "09:00")),
            doneTaskIds = setOf(10),
        )
        assertTrue(items.single() is TaskItem)
        assertTrue((items.single() as TaskItem).done)
    }

    @Test
    fun `tarefa sem horario nao entra na trilha`() {
        val items = DayTimeline.build(emptyList(), emptySet(), day, zone, tasks = listOf(task(10, time = null)))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `tarefa de outro dia nao entra na trilha`() {
        val items = DayTimeline.build(
            listOf(routine(1, "07:00", 30)),
            emptySet(),
            day,
            zone,
            tasks = listOf(task(10, "09:00", date = day.plusDays(1))),
        )
        assertEquals(1, items.size)
        assertTrue(items.filterIsInstance<TaskItem>().isEmpty())
    }
}
