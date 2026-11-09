package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MissedRemindersTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    // Quarta-feira, 28/10/2026.
    private val wed = LocalDate.of(2026, 10, 28)

    private fun millis(date: LocalDate, time: LocalTime = LocalTime.of(9, 0)): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun task(
        id: Long,
        anchorDate: LocalDate?,
        time: LocalTime = LocalTime.of(9, 0),
        repeat: ReminderRepeat = ReminderRepeat.NONE,
    ) = Task(
        id = id,
        title = "T$id",
        dueDate = anchorDate?.toEpochDay(),
        reminderAt = anchorDate?.let { millis(it, time) },
        repeat = repeat,
    )

    private fun missed(tasks: List<Task>, lastSeen: Long, now: Long) =
        MissedReminders.between(tasks, lastSeen, now, zone)

    @Test
    fun `tarefa sem lembrete e ignorada`() {
        val noReminder = Task(id = 1, title = "sem", dueDate = wed.toEpochDay(), reminderAt = null)
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(noReminder), millis(wed.minusDays(1)), millis(wed.plusDays(1))))
    }

    @Test
    fun `one-time dentro da janela conta uma vez`() {
        val t = task(1, wed, LocalTime.of(9, 0))
        val result = missed(listOf(t), millis(wed.minusDays(1)), millis(wed, LocalTime.of(12, 0)))
        assertEquals(listOf(MissedReminders.Occurrence(1, millis(wed, LocalTime.of(9, 0)))), result)
    }

    @Test
    fun `one-time antes do lastSeen ou depois do now nao conta`() {
        val t = task(1, wed, LocalTime.of(9, 0))
        // Disparo às 9h; lastSeen às 10h do mesmo dia -> já passou, não é perdido.
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(t), millis(wed, LocalTime.of(10, 0)), millis(wed.plusDays(2))))
        // now antes do disparo -> ainda não deveria ter tocado.
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(t), millis(wed.minusDays(1)), millis(wed, LocalTime.of(8, 0))))
    }

    @Test
    fun `borda inferior exclusiva e superior inclusiva`() {
        val t = task(1, wed, LocalTime.of(9, 0))
        val fireAt = millis(wed, LocalTime.of(9, 0))
        // lastSeen == instante do disparo -> excluído (já processado).
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(t), fireAt, fireAt + 1))
        // now == instante do disparo -> incluído.
        assertEquals(listOf(MissedReminders.Occurrence(1, fireAt)), missed(listOf(t), fireAt - 1, fireAt))
    }

    @Test
    fun `now menor ou igual a lastSeen retorna vazio`() {
        val t = task(1, wed, LocalTime.of(9, 0))
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(t), millis(wed), millis(wed)))
        assertEquals(emptyList<MissedReminders.Occurrence>(), missed(listOf(t), millis(wed.plusDays(1)), millis(wed)))
    }

    @Test
    fun `diaria com aparelho desligado tres dias conta cada ocorrencia`() {
        val daily = task(1, wed, LocalTime.of(9, 0), ReminderRepeat.DAILY)
        // Desligou qui 29 ~8h, ligou dom 1/11 ~12h: perdeu qui, sex, sab, dom (9h de cada).
        val lastSeen = millis(wed.plusDays(1), LocalTime.of(8, 0)) // qui 08:00
        val now = millis(wed.plusDays(4), LocalTime.of(12, 0)) // dom 12:00
        val result = missed(listOf(daily), lastSeen, now)
        val expected = (1L..4L).map { MissedReminders.Occurrence(1, millis(wed.plusDays(it), LocalTime.of(9, 0))) }
        assertEquals(expected, result)
    }

    @Test
    fun `semanal so conta o mesmo dia da semana`() {
        val weekly = task(1, wed, LocalTime.of(9, 0), ReminderRepeat.WEEKLY) // quarta
        val lastSeen = millis(wed, LocalTime.of(10, 0)) // depois do disparo desta quarta
        val now = millis(wed.plusDays(14), LocalTime.of(12, 0)) // 2 quartas à frente
        val result = missed(listOf(weekly), lastSeen, now)
        assertEquals(
            listOf(
                MissedReminders.Occurrence(1, millis(wed.plusDays(7), LocalTime.of(9, 0))),
                MissedReminders.Occurrence(1, millis(wed.plusDays(14), LocalTime.of(9, 0))),
            ),
            result,
        )
    }

    @Test
    fun `mensal dia 31 cai no ultimo dia de fevereiro`() {
        val jan31 = LocalDate.of(2027, 1, 31)
        val monthly = task(1, jan31, LocalTime.of(9, 0), ReminderRepeat.MONTHLY)
        // Janela cobre fevereiro (2027 não é bissexto -> 28).
        val lastSeen = millis(jan31, LocalTime.of(10, 0))
        val now = millis(LocalDate.of(2027, 3, 1), LocalTime.of(12, 0))
        val result = missed(listOf(monthly), lastSeen, now)
        assertEquals(
            listOf(MissedReminders.Occurrence(1, millis(LocalDate.of(2027, 2, 28), LocalTime.of(9, 0)))),
            result,
        )
    }

    @Test
    fun `varias tarefas saem ordenadas por horario`() {
        val a = task(1, wed, LocalTime.of(18, 0))
        val b = task(2, wed, LocalTime.of(7, 0))
        val c = task(3, wed.plusDays(1), LocalTime.of(6, 0))
        val lastSeen = millis(wed.minusDays(1))
        val now = millis(wed.plusDays(1), LocalTime.of(12, 0))
        val result = missed(listOf(a, b, c), lastSeen, now)
        assertEquals(listOf(2L, 1L, 3L), result.map { it.taskId })
    }
}
