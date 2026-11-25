package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline.Block
import com.kalendrys.app.domain.DayTimeline.Gap
import com.kalendrys.app.domain.DayTimeline.Link
import com.kalendrys.app.domain.DayTimeline.TaskItem
import com.kalendrys.app.testutil.TEST_CREATED_AT
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
        return Task(id = id, title = "T$id", dueDate = date.toEpochDay(), reminderAt = reminderAt, createdAt = TEST_CREATED_AT)
    }

    private fun overrides(vararg ov: RoutineOverride): Map<Long, List<RoutineOverride>> =
        ov.toList().groupBy { it.routineId }

    private fun skip(routineId: Long, date: LocalDate = day) =
        RoutineOverride(routineId = routineId, epochDay = date.toEpochDay(), type = RoutineOverrideType.SKIP, createdAt = TEST_CREATED_AT)

    private fun reschedule(routineId: Long, date: LocalDate = day, newStart: String? = null, newDate: LocalDate? = null): RoutineOverride {
        val minute = newStart?.let { val (h, m) = it.split(":").map(String::toInt); h * 60 + m }
        return RoutineOverride(
            routineId = routineId,
            epochDay = date.toEpochDay(),
            type = RoutineOverrideType.RESCHEDULE,
            newStartMinute = minute,
            newEpochDay = newDate?.toEpochDay(),
            createdAt = TEST_CREATED_AT,
        )
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

    @Test
    fun `ocorrencia com SKIP some da trilha`() {
        val items = DayTimeline.build(
            listOf(routine(1, "07:00", 30), routine(2, "12:00", 30)),
            emptySet(),
            day,
            zone,
            overrides = overrides(skip(1)),
        )
        assertEquals(listOf(2L), items.filterIsInstance<Block>().map { it.routine.id })
    }

    @Test
    fun `RESCHEDULE move a ocorrencia para o novo horario`() {
        val items = DayTimeline.build(
            listOf(routine(1, "07:00", 30)),
            emptySet(),
            day,
            zone,
            overrides = overrides(reschedule(1, newStart = "15:30")),
        )
        val block = items.filterIsInstance<Block>().single()
        assertEquals(1L, block.routine.id)
        assertEquals(15 * 60 + 30, block.startMinute)
        assertEquals(15 * 60 + 30 + 30, block.endMinute)
    }

    @Test
    fun `RESCHEDULE para outro dia sai do original e entra no novo horario`() {
        val routines = listOf(routine(1, "07:00", 30))
        val ov = overrides(reschedule(1, date = day, newStart = "09:00", newDate = day.plusDays(1)))
        // Sai do dia original.
        assertTrue(DayTimeline.build(routines, emptySet(), day, zone, overrides = ov).filterIsInstance<Block>().isEmpty())
        // Entra no dia alvo, no novo horário (a remarcação ganha do horário natural).
        val block = DayTimeline.build(routines, emptySet(), day.plusDays(1), zone, overrides = ov)
            .filterIsInstance<Block>()
            .single()
        assertEquals(9 * 60, block.startMinute)
    }

    private fun build(vararg routines: Routine, tasks: List<Task>) =
        DayTimeline.build(routines.toList(), emptySet(), day, zone, tasks = tasks)

    @Test
    fun `intervalo curto entre rotina e tarefa fica tracejado sem linha de tempo livre`() {
        // Rotina 07:00–07:30, tarefa às 07:40: 10 min de folga (< 30) → tracejado, sem Gap.
        val items = build(routine(1, "07:00", 30), tasks = listOf(task(10, "07:40")))
        assertTrue(items.filterIsInstance<Gap>().isEmpty())
        assertEquals(Link.DASHED, items.filterIsInstance<Block>().single().linkBelow)
        assertEquals(Link.DASHED, items.filterIsInstance<TaskItem>().single().linkAbove)
    }

    @Test
    fun `intervalo de 30 min ou mais entre rotina e tarefa vira linha de tempo livre`() {
        // Rotina 07:00–07:30, tarefa às 09:00: 90 min de folga (>= 30) → tracejado + Gap.
        val items = build(routine(1, "07:00", 30), tasks = listOf(task(10, "09:00")))
        assertEquals(Gap(startMinute = 7 * 60 + 30, minutes = 90), items.filterIsInstance<Gap>().single())
        assertEquals(Link.DASHED, items.filterIsInstance<Block>().single().linkBelow)
        assertEquals(Link.DASHED, items.filterIsInstance<TaskItem>().single().linkAbove)
    }

    @Test
    fun `entre tarefas a ligacao segue a folga como entre rotinas`() {
        // 08:00, 08:10 (10 min → tracejado sem Gap) e 09:00 (50 min → tracejado + Gap).
        val items = DayTimeline.build(
            emptyList(),
            emptySet(),
            day,
            zone,
            tasks = listOf(task(10, "08:00"), task(11, "08:10"), task(12, "09:00")),
        )
        val tasks = items.filterIsInstance<TaskItem>()
        assertEquals(listOf(Link.NONE, Link.DASHED, Link.DASHED), tasks.map { it.linkAbove })
        assertEquals(Gap(startMinute = 8 * 60 + 10, minutes = 50), items.filterIsInstance<Gap>().single())
    }

    @Test
    fun `tarefa dentro do span de uma rotina encosta com linha cheia e sem gap`() {
        // Rotina 09:00–10:00, tarefa às 09:30: começa antes do fim → encostada (SOLID), sem Gap.
        val items = build(routine(1, "09:00", 60), tasks = listOf(task(10, "09:30")))
        assertTrue(items.filterIsInstance<Gap>().isEmpty())
        val block = items.filterIsInstance<Block>().single()
        val taskItem = items.filterIsInstance<TaskItem>().single()
        assertEquals(Link.SOLID, block.linkBelow)
        assertEquals(Link.SOLID, taskItem.linkAbove)
        assertEquals(Link.NONE, taskItem.linkBelow)
    }

    @Test
    fun `no mesmo minuto a rotina com duracao vem antes da tarefa pontual`() {
        val items = build(routine(1, "08:00", 30), tasks = listOf(task(10, "08:00")))
        assertTrue(items.first() is Block)
        assertTrue(items[1] is TaskItem)
        // A tarefa cai dentro do span da rotina → encostada, sem intervalo.
        assertEquals(Link.SOLID, (items[1] as TaskItem).linkAbove)
    }

    // --- P5 (v1.8.0, E3): bloco pulado visível, marcado, sem contar no dia ---

    private fun buildWithSkipped(routines: List<Routine>, vararg ov: RoutineOverride) =
        DayTimeline.build(routines, emptySet(), day, zone, overrides = overrides(*ov), includeSkipped = true)

    @Test
    fun `com includeSkipped o pulado continua na trilha marcado, no horario normal e sem ligacoes`() {
        val items = buildWithSkipped(listOf(routine(1, "07:00", 30), routine(2, "12:00", 30)), skip(1))

        val skipped = items.filterIsInstance<Block>().single { it.routine.id == 1L }
        assertTrue(skipped.skipped)
        assertFalse(skipped.isActive)
        assertEquals(7 * 60, skipped.startMinute)
        assertEquals(Link.NONE, skipped.linkAbove)
        assertEquals(Link.NONE, skipped.linkBelow)
        assertFalse(items.filterIsInstance<Block>().single { it.routine.id == 2L }.skipped)
        assertEquals(listOf(1L, 2L), items.filterIsInstance<Block>().map { it.routine.id })
    }

    @Test
    fun `pulado nao conta nas lacunas nem nas ligacoes dos outros`() {
        val routines = listOf(routine(1, "07:00", 30), routine(2, "08:00", 30), routine(3, "09:00", 30))

        val items = buildWithSkipped(routines, skip(2))
        // Os ativos ficam exatamente como se o pulado não existisse: lacuna 07:30–09:00 inteira.
        val withoutSkipped = DayTimeline.build(listOf(routines[0], routines[2]), emptySet(), day, zone)
        assertEquals(withoutSkipped, items.filter { it.isActive })
        assertEquals(Gap(startMinute = 7 * 60 + 30, minutes = 90), items.filterIsInstance<Gap>().single())
        // Ordem: o pulado entra pelo início, depois da lacuna que começa antes dele.
        assertEquals(listOf("routine_1", "gap_450", "routine_2", "routine_3"), items.map { it.key })
    }

    @Test
    fun `pulado dentro de um bloco ativo nao quebra a ligacao encostada`() {
        val routines = listOf(routine(1, "07:00", 120), routine(2, "08:00", 30), routine(3, "08:30", 30))

        val items = buildWithSkipped(routines, skip(2))

        val first = items.filterIsInstance<Block>().single { it.routine.id == 1L }
        val third = items.filterIsInstance<Block>().single { it.routine.id == 3L }
        assertEquals(Link.SOLID, first.linkBelow)
        assertEquals(Link.SOLID, third.linkAbove)
        assertTrue(items.filterIsInstance<Gap>().isEmpty())
    }

    @Test
    fun `remarcada para outro dia nao aparece como pulada`() {
        val items = buildWithSkipped(listOf(routine(1, "07:00", 30)), reschedule(1, newDate = day.plusDays(1)))

        assertTrue(items.isEmpty())
    }

    @Test
    fun `dia so com o pulado mostra so ele e desfazer o pular devolve o bloco ativo`() {
        val routines = listOf(routine(1, "07:00", 30))

        val skippedDay = buildWithSkipped(routines, skip(1))
        assertEquals(1, skippedDay.size)
        assertTrue((skippedDay.single() as Block).skipped)

        // Desfazer = tirar a exceção do dia: o bloco volta normal, igual a um dia sem exceção.
        val undone = buildWithSkipped(routines)
        assertEquals(build(*routines.toTypedArray()), undone)
        assertFalse((undone.single() as Block).skipped)
    }

    @Test
    fun `sem includeSkipped nada muda - quem nao pede nao recebe o pulado`() {
        val routines = listOf(routine(1, "07:00", 30), routine(2, "12:00", 30))

        val items = DayTimeline.build(routines, emptySet(), day, zone, overrides = overrides(skip(1)))

        assertEquals(listOf(2L), items.filterIsInstance<Block>().map { it.routine.id })
        assertTrue(items.all { it.isActive })
    }

    // --- A1 do Esquadro na E3: o bloco carrega o dia da OCORRÊNCIA (onde mora a exceção) ---

    private fun blockOn(date: LocalDate, routines: List<Routine>, vararg ov: RoutineOverride): Block =
        DayTimeline.build(routines, emptySet(), date, zone, overrides = overrides(*ov), includeSkipped = true)
            .filterIsInstance<Block>()
            .single()

    @Test
    fun `bloco normal e remarcado no proprio dia carregam o dia mostrado`() {
        val routines = listOf(routine(1, "07:00", 30))

        assertEquals(day, blockOn(day, routines).occurrenceDate)
        assertEquals(day, blockOn(day, routines, reschedule(1, newStart = "15:30")).occurrenceDate)
    }

    @Test
    fun `bloco remarcado vindo de outro dia carrega o dia de origem`() {
        // Só aos sábados (24/10/2026); remarcado de sábado para domingo às 09:00.
        val routines = listOf(routine(1, "07:00", 30, days = RoutineDays.bit(day.dayOfWeek)))
        val sunday = day.plusDays(1)

        val block = blockOn(sunday, routines, reschedule(1, date = day, newStart = "09:00", newDate = sunday))

        assertEquals(day, block.occurrenceDate)
        assertEquals(9 * 60, block.startMinute)
    }

    @Test
    fun `pular no dia de origem troca a remarcacao - o bloco some do dia novo e aparece pulado na origem`() {
        val routines = listOf(routine(1, "07:00", 30, days = RoutineDays.bit(day.dayOfWeek)))
        val sunday = day.plusDays(1)
        // Uma exceção por dia: o SKIP no dia de origem substitui a remarcação.
        val skipped = overrides(skip(1, date = day))

        assertTrue(DayTimeline.build(routines, emptySet(), sunday, zone, overrides = skipped, includeSkipped = true).isEmpty())
        val atOrigin = blockOn(day, routines, skip(1, date = day))
        assertTrue(atOrigin.skipped)
        assertEquals(day, atOrigin.occurrenceDate)
    }
}
