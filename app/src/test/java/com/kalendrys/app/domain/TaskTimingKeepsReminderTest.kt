package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaskTiming.keepsReminderOf] (v1.9.0, E2): o detalhe avisa de lembrete no passado só quando o
 * mostrado não é mais o da abertura. Mesmo horário e mesma repetição: mantém.
 */
class TaskTimingKeepsReminderTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 11, 25)
    private val timing = TaskTiming(day, LocalTime.of(9, 0), ReminderRepeat.WEEKLY)
    private val task = timing.applyTo(Task(id = 1, title = "Regar", createdAt = TEST_CREATED_AT), zone)

    @Test
    fun `o mesmo timing mantem o lembrete`() = assertTrue(timing.keepsReminderOf(task, zone))

    @Test
    fun `outra hora, outro dia ou outra repeticao nao mantem`() {
        assertFalse(timing.copy(time = LocalTime.of(9, 30)).keepsReminderOf(task, zone))
        assertFalse(timing.copy(date = day.plusDays(1)).keepsReminderOf(task, zone))
        assertFalse(timing.copy(repeat = ReminderRepeat.DAILY).keepsReminderOf(task, zone))
        assertFalse(timing.copy(time = null).keepsReminderOf(task, zone))
    }

    @Test
    fun `personalizada antiga sem dia lida como nao repete mantem o lembrete`() {
        val broken = task.copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0, intervalUnit = null)
        assertTrue(TaskTiming.of(broken, zone).keepsReminderOf(broken, zone))
    }

    @Test
    fun `personalizada com outros dias nao mantem`() {
        val custom = timing.copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0b0000011)
        val stored = custom.applyTo(Task(id = 2, title = "Treino", createdAt = TEST_CREATED_AT), zone)
        assertTrue(custom.keepsReminderOf(stored, zone))
        assertFalse(custom.copy(byWeekdays = 0b0000101).keepsReminderOf(stored, zone))
    }
}
