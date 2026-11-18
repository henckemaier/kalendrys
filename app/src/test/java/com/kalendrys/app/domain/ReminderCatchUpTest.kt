package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plano do catch-up (ADR-0003, seção 6). Os casos de janela do antigo MissedRemindersTest migraram
 * para cá, agora sobre o plano (atrasada x perdida).
 */
class ReminderCatchUpTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    // Quarta-feira, 28/10/2026.
    private val wed = LocalDate.of(2026, 10, 28)

    private fun millis(date: LocalDate, time: LocalTime = LocalTime.of(9, 0), z: ZoneId = zone): Long =
        date.atTime(time).atZone(z).toInstant().toEpochMilli()

    private fun minutes(m: Long) = m * 60_000L

    private fun task(
        id: Long,
        anchorDate: LocalDate?,
        time: LocalTime = LocalTime.of(9, 0),
        repeat: ReminderRepeat = ReminderRepeat.NONE,
        every: Int = 1,
        unit: RecurrenceUnit? = null,
        byWeekdays: Int = 0,
        z: ZoneId = zone,
    ) = Task(
        id = id,
        title = "T$id",
        dueDate = anchorDate?.toEpochDay(),
        reminderAt = anchorDate?.let { millis(it, time, z) },
        repeat = repeat,
        intervalEvery = every,
        intervalUnit = unit,
        byWeekdays = byWeekdays,
    )

    private fun plan(
        tasks: List<Task>,
        lastSeen: Long,
        now: Long,
        lastCompletions: Map<Long, Long> = emptyMap(),
        completionDays: Map<Long, Set<LocalDate>> = emptyMap(),
        z: ZoneId = zone,
    ) = ReminderCatchUp.plan(tasks, lastCompletions, completionDays, lastSeen, now, z)

    private fun late(vararg occurrences: Pair<Long, Long>) =
        occurrences.map { (id, at) -> ReminderCatchUp.Occurrence(id, at) }

    // --- Tolerância e limites ---

    @Test
    fun `tolerancia e de 60 minutos`() {
        assertEquals(60L, ReminderCatchUp.LATE_TOLERANCE_MINUTES)
    }

    @Test
    fun `vencida dentro da tolerancia sai como lembrete`() {
        val nine = millis(wed)
        val result = plan(listOf(task(1, wed)), lastSeen = nine - minutes(5), now = nine + minutes(3))
        assertEquals(ReminderCatchUp.Plan(late(1L to nine), missedCount = 0), result)
    }

    @Test
    fun `vencida fora da tolerancia entra como perdida`() {
        val nine = millis(wed)
        val result = plan(listOf(task(1, wed)), lastSeen = nine - minutes(5), now = nine + minutes(61))
        assertEquals(ReminderCatchUp.Plan(emptyList(), missedCount = 1), result)
    }

    @Test
    fun `limite exato da tolerancia ainda e atrasada e um ms depois e perdida`() {
        val nine = millis(wed)
        val t = listOf(task(1, wed))
        assertEquals(late(1L to nine), plan(t, nine - 1, nine + minutes(60)).late)
        val after = plan(t, nine - 1, nine + minutes(60) + 1)
        assertEquals(emptyList<ReminderCatchUp.Occurrence>(), after.late)
        assertEquals(1, after.missedCount)
    }

    @Test
    fun `borda inferior da janela exclusiva e superior inclusiva`() {
        val nine = millis(wed)
        val t = listOf(task(1, wed))
        // lastSeen == ocorrência: já processada.
        assertTrue(plan(t, lastSeen = nine, now = nine + 1).isEmpty)
        // now == ocorrência: entra (atraso zero).
        assertEquals(late(1L to nine), plan(t, lastSeen = nine - 1, now = nine).late)
    }

    @Test
    fun `ocorrencia futura fica para o agendamento`() {
        val t = listOf(task(1, wed))
        assertTrue(plan(t, millis(wed.minusDays(1)), millis(wed, LocalTime.of(8, 59))).isEmpty)
    }

    @Test
    fun `tarefa sem lembrete e ignorada`() {
        val noReminder = Task(id = 1, title = "sem", dueDate = wed.toEpochDay(), reminderAt = null)
        assertTrue(plan(listOf(noReminder), millis(wed.minusDays(1)), millis(wed.plusDays(1))).isEmpty)
    }

    @Test
    fun `relogio voltou da janela vazia`() {
        val t = listOf(task(1, wed, repeat = ReminderRepeat.DAILY))
        assertTrue(plan(t, lastSeen = millis(wed), now = millis(wed)).isEmpty)
        assertTrue(plan(t, lastSeen = millis(wed.plusDays(1)), now = millis(wed)).isEmpty)
    }

    // --- Mais recente por tarefa ---

    @Test
    fun `diaria com tres dias fora so a de hoje sai e as anteriores sao perdidas`() {
        val daily = task(1, wed, repeat = ReminderRepeat.DAILY)
        // Desligou qui 08:00, ligou dom 09:10: qui, sex, sab perdidas; a de dom (9:00) atrasada.
        val now = millis(wed.plusDays(4), LocalTime.of(9, 10))
        val result = plan(listOf(daily), lastSeen = millis(wed.plusDays(1), LocalTime.of(8, 0)), now = now)
        assertEquals(late(1L to millis(wed.plusDays(4))), result.late)
        assertEquals(3, result.missedCount)
    }

    @Test
    fun `diaria com quatro dias fora e a mais recente tambem passou conta quatro perdidas`() {
        val daily = task(1, wed, repeat = ReminderRepeat.DAILY)
        val result = plan(listOf(daily), millis(wed.plusDays(1), LocalTime.of(8, 0)), millis(wed.plusDays(4), LocalTime.of(12, 0)))
        assertEquals(ReminderCatchUp.Plan(emptyList(), missedCount = 4), result)
    }

    @Test
    fun `semanal so conta o mesmo dia da semana`() {
        val weekly = task(1, wed, repeat = ReminderRepeat.WEEKLY)
        val result = plan(listOf(weekly), millis(wed, LocalTime.of(10, 0)), millis(wed.plusDays(14), LocalTime.of(12, 0)))
        assertEquals(2, result.missedCount)
    }

    @Test
    fun `mensal dia 31 cai no ultimo dia de fevereiro`() {
        val jan31 = LocalDate.of(2027, 1, 31)
        val monthly = task(1, jan31, repeat = ReminderRepeat.MONTHLY)
        val feb28 = millis(LocalDate.of(2027, 2, 28))
        val result = plan(listOf(monthly), millis(jan31, LocalTime.of(10, 0)), feb28 + minutes(30))
        assertEquals(late(1L to feb28), result.late)
    }

    @Test
    fun `varias tarefas atrasadas saem em ordem de horario`() {
        val a = task(1, wed, LocalTime.of(9, 30))
        val b = task(2, wed, LocalTime.of(9, 10))
        val c = task(3, wed, LocalTime.of(9, 50))
        val result = plan(listOf(a, b, c), millis(wed, LocalTime.of(9, 0)), millis(wed, LocalTime.of(10, 0)))
        assertEquals(listOf(2L, 1L, 3L), result.late.map { it.taskId })
        assertEquals(0, result.missedCount)
    }

    @Test
    fun `tarefa A atrasada e B no horario saem as duas`() {
        val a = task(1, wed, LocalTime.of(9, 0))
        val b = task(2, wed, LocalTime.of(9, 5))
        val result = plan(listOf(a, b), millis(wed, LocalTime.of(8, 0)), millis(wed, LocalTime.of(9, 5)))
        assertEquals(late(1L to millis(wed), 2L to millis(wed, LocalTime.of(9, 5))), result.late)
    }

    // --- Concluídas ---

    @Test
    fun `tarefa unica concluida fica de fora`() {
        val t = task(1, wed)
        val result = plan(
            listOf(t),
            millis(wed, LocalTime.of(8, 0)),
            millis(wed, LocalTime.of(9, 5)),
            lastCompletions = mapOf(1L to millis(wed, LocalTime.of(8, 30))),
        )
        assertTrue(result.isEmpty)
    }

    @Test
    fun `repetida concluida no dia da ocorrencia fica de fora e nos outros dias conta`() {
        val daily = task(1, wed, repeat = ReminderRepeat.DAILY)
        val now = millis(wed.plusDays(2), LocalTime.of(9, 5))
        val lastSeen = millis(wed, LocalTime.of(10, 0))
        // Concluída hoje (sex): a de hoje não sai; a de qui (não concluída) é perdida.
        val doneToday = plan(listOf(daily), lastSeen, now, completionDays = mapOf(1L to setOf(wed.plusDays(2))))
        assertEquals(ReminderCatchUp.Plan(emptyList(), missedCount = 1), doneToday)
        // Concluída qui: a de qui some; a de hoje sai.
        val doneThu = plan(listOf(daily), lastSeen, now, completionDays = mapOf(1L to setOf(wed.plusDays(1))))
        assertEquals(ReminderCatchUp.Plan(late(1L to millis(wed.plusDays(2))), missedCount = 0), doneThu)
    }

    // --- Repetição personalizada (defeito 4 do ADR-0003) ---

    @Test
    fun `personalizada a cada 2 dias conta so os dias do intervalo`() {
        val custom = task(1, wed, repeat = ReminderRepeat.CUSTOM, every = 2, unit = RecurrenceUnit.DAYS)
        // Ocorre qua 28, sex 30, dom 1/11. Janela: qua 10:00 até dom 09:05.
        val result = plan(listOf(custom), millis(wed, LocalTime.of(10, 0)), millis(wed.plusDays(4), LocalTime.of(9, 5)))
        assertEquals(late(1L to millis(wed.plusDays(4))), result.late)
        assertEquals(1, result.missedCount) // só sex 30; qui e sab não são dias da regra
    }

    @Test
    fun `personalizada por dias da semana conta so os dias marcados`() {
        val mask = RoutineDays.bit(DayOfWeek.MONDAY) or RoutineDays.bit(DayOfWeek.FRIDAY)
        val custom = task(1, wed, repeat = ReminderRepeat.CUSTOM, byWeekdays = mask)
        // Janela qua 28 10:00 até qua 4/11 12:00: sex 30 e seg 2/11.
        val result = plan(listOf(custom), millis(wed, LocalTime.of(10, 0)), millis(wed.plusDays(7), LocalTime.of(12, 0)))
        assertEquals(ReminderCatchUp.Plan(emptyList(), missedCount = 2), result)
    }

    @Test
    fun `personalizada a cada 2 semanas pula a semana do meio`() {
        val custom = task(1, wed, repeat = ReminderRepeat.CUSTOM, every = 2, unit = RecurrenceUnit.WEEKS)
        // Ocorre qua 28/10 e qua 11/11; qua 4/11 não. Janela: qua 28 10:00 até qua 11/11 09:05.
        val result = plan(listOf(custom), millis(wed, LocalTime.of(10, 0)), millis(wed.plusDays(14), LocalTime.of(9, 5)))
        assertEquals(ReminderCatchUp.Plan(late(1L to millis(wed.plusDays(14))), missedCount = 0), result)
    }

    @Test
    fun `dias uteis com a janela passando pelo fim de semana nao contam sabado e domingo`() {
        val weekdays = task(1, wed, repeat = ReminderRepeat.WEEKDAYS)
        // Janela sex 30 10:00 até seg 2/11 09:05: só a de segunda, atrasada; sab e dom não existem.
        val result = plan(listOf(weekdays), millis(wed.plusDays(2), LocalTime.of(10, 0)), millis(wed.plusDays(5), LocalTime.of(9, 5)))
        assertEquals(ReminderCatchUp.Plan(late(1L to millis(wed.plusDays(5))), missedCount = 0), result)
    }

    // --- Virada de dia e horário de verão ---

    @Test
    fun `virada de dia entrega a ocorrencia da meia-noite`() {
        val midnight = task(1, wed, LocalTime.MIDNIGHT, repeat = ReminderRepeat.DAILY)
        val thuMidnight = millis(wed.plusDays(1), LocalTime.MIDNIGHT)
        val result = plan(listOf(midnight), millis(wed, LocalTime.of(23, 30)), thuMidnight + minutes(20))
        assertEquals(ReminderCatchUp.Plan(late(1L to thuMidnight), missedCount = 0), result)
    }

    @Test
    fun `horario de verao mede o atraso em tempo real`() {
        val ny = ZoneId.of("America/New_York")
        // 08/03/2026: 02:00 vira 03:00. Diária às 01:30: entre 01:30 e 03:20 passaram só 50 min reais.
        val day = LocalDate.of(2026, 3, 8)
        val daily = task(1, day.minusDays(3), LocalTime.of(1, 30), ReminderRepeat.DAILY, z = ny)
        val fired = millis(day, LocalTime.of(1, 30), ny)
        val now = millis(day, LocalTime.of(3, 20), ny)
        assertEquals(minutes(50), now - fired)
        val result = plan(listOf(daily), millis(day, LocalTime.of(1, 0), ny), now, z = ny)
        assertEquals(ReminderCatchUp.Plan(late(1L to fired), missedCount = 0), result)
    }

    @Test
    fun `horario inexistente no horario de verao toca no horario deslocado`() {
        val ny = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        // 02:30 não existe neste dia: a ocorrência vai para 03:30 EDT.
        val daily = task(1, day.minusDays(3), LocalTime.of(2, 30), ReminderRepeat.DAILY, z = ny)
        val shifted = millis(day, LocalTime.of(3, 30), ny)
        val result = plan(listOf(daily), millis(day, LocalTime.of(1, 0), ny), shifted + minutes(5), z = ny)
        assertEquals(late(1L to shifted), result.late)
    }

    // --- Predicado compartilhado com o diagnóstico (D-71-1) ---

    @Test
    fun `isDiscarded - repetida no dia com conclusao e descartada, em outro dia nao`() {
        val daily = task(1, wed, repeat = ReminderRepeat.DAILY)
        val done = setOf(wed)

        assertTrue(ReminderCatchUp.isDiscarded(daily, millis(wed), done, zone))
        assertEquals(false, ReminderCatchUp.isDiscarded(daily, millis(wed.plusDays(1)), done, zone))
        assertEquals(false, ReminderCatchUp.isDiscarded(daily, millis(wed), emptySet(), zone))
    }

    @Test
    fun `isDiscarded - tarefa sem repeticao nunca e descartada pelo dia concluido`() {
        val once = task(1, wed)

        assertEquals(false, ReminderCatchUp.isDiscarded(once, millis(wed), setOf(wed), zone))
    }

    @Test
    fun `isDiscarded - o dia da ocorrencia e o do fuso`() {
        // 23:30 de quarta em São Paulo já é quinta em UTC.
        val daily = task(1, wed, LocalTime.of(23, 30), ReminderRepeat.DAILY)
        val fired = millis(wed, LocalTime.of(23, 30))

        assertTrue(ReminderCatchUp.isDiscarded(daily, fired, setOf(wed), zone))
        assertEquals(false, ReminderCatchUp.isDiscarded(daily, fired, setOf(wed), ZoneId.of("UTC")))
    }

    // --- Última ocorrência que já tocou (NB-E4-A da v1.8.0) ---

    @Test
    fun `ultima ocorrencia desde o dia concluido ate agora`() {
        val daily = task(1, wed.minusDays(10), time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY)
        val yesterday = wed.minusDays(1)
        // Antes das 10h de hoje: a última é a de ontem.
        assertEquals(yesterday, ReminderCatchUp.lastOccurrenceSince(daily, yesterday, millis(wed, LocalTime.of(9, 0)), zone)?.day(zone))
        // Depois das 10h: a de hoje.
        assertEquals(wed, ReminderCatchUp.lastOccurrenceSince(daily, yesterday, millis(wed, LocalTime.of(11, 0)), zone)?.day(zone))
        // Desde anteontem, antes das 10h: ainda a de ontem.
        assertEquals(yesterday, ReminderCatchUp.lastOccurrenceSince(daily, wed.minusDays(2), millis(wed, LocalTime.of(9, 0)), zone)?.day(zone))
        // Exatamente no horário de hoje: já tocou.
        assertEquals(wed, ReminderCatchUp.lastOccurrenceSince(daily, yesterday, millis(wed, LocalTime.of(10, 0)), zone)?.day(zone))
    }

    @Test
    fun `sem ocorrencia na janela da null`() {
        val weekly = task(1, wed, time = LocalTime.of(10, 0), repeat = ReminderRepeat.WEEKLY)
        // Semanal de quarta: de segunda até terça à noite não tocou nada.
        assertEquals(null, ReminderCatchUp.lastOccurrenceSince(weekly, wed.minusDays(2), millis(wed.minusDays(1), LocalTime.of(23, 0)), zone))
        assertEquals(null, ReminderCatchUp.lastOccurrenceSince(task(2, null), wed, millis(wed, LocalTime.of(23, 0)), zone))
    }
}
