package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.testutil.TEST_CREATED_AT
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cada [TaskChange] aplica só o seu campo e recusa o estado inválido (ADR-0002, 5.3 e 15.2). */
class TaskChangeTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val base = Task(
        id = 7,
        title = "Pagar contas",
        notes = "boleto",
        location = "casa",
        shoppingListId = 3,
        dueDate = LocalDate.of(2026, 11, 25).toEpochDay(),
        createdAt = TEST_CREATED_AT,
    )

    @Test
    fun `titulo e aparado e so ele muda`() {
        val result = TaskChange.Title("  Pagar luz  ").applyTo(base, zone)
        assertEquals(base.copy(title = "Pagar luz"), result)
    }

    @Test
    fun `titulo vazio ou em branco e invalido`() {
        assertNull(TaskChange.Title("").applyTo(base, zone))
        assertNull(TaskChange.Title("   ").applyTo(base, zone))
    }

    @Test
    fun `observacoes e local sao aparados e nao mexem no resto`() {
        assertEquals(base.copy(notes = "pix"), TaskChange.Notes(" pix ").applyTo(base, zone))
        assertEquals(base.copy(location = ""), TaskChange.Location("  ").applyTo(base, zone))
    }

    @Test
    fun `data hora e repeticao entram juntos`() {
        val timing = TaskTiming(date = LocalDate.of(2026, 12, 1), time = LocalTime.of(8, 30), repeat = ReminderRepeat.WEEKLY)
        val result = TaskChange.Timing(timing).applyTo(base, zone)!!

        assertEquals(LocalDate.of(2026, 12, 1).toEpochDay(), result.dueDate)
        assertEquals(timing.reminderAt(zone), result.reminderAt)
        assertEquals(ReminderRepeat.WEEKLY, result.repeat)
        // O resto da tarefa não muda.
        assertEquals(base.copy(dueDate = result.dueDate, reminderAt = result.reminderAt, repeat = result.repeat), result)
    }

    @Test
    fun `tirar a data tira a repeticao junto`() {
        val repeating = base.copy(repeat = ReminderRepeat.DAILY)
        val result = TaskChange.Timing(TaskTiming(date = null, repeat = ReminderRepeat.DAILY)).applyTo(repeating, zone)!!
        assertNull(result.dueDate)
        assertNull(result.reminderAt)
        assertEquals(ReminderRepeat.NONE, result.repeat)
    }

    @Test
    fun `repeticao personalizada sem dia utilizavel e invalida`() {
        val date = LocalDate.of(2026, 12, 1)
        // Nem dias nem intervalo com unidade.
        assertNull(TaskChange.Timing(TaskTiming(date = date, repeat = ReminderRepeat.CUSTOM)).applyTo(base, zone))
        // Só bits fora dos sete dias.
        assertNull(TaskChange.Timing(TaskTiming(date = date, repeat = ReminderRepeat.CUSTOM, byWeekdays = 0b1000_0000)).applyTo(base, zone))
    }

    @Test
    fun `repeticao personalizada com dias ou com intervalo vale`() {
        val date = LocalDate.of(2026, 12, 1)
        val byDays = TaskTiming(date = date, repeat = ReminderRepeat.CUSTOM, byWeekdays = 0b0000_0101)
        val byInterval = TaskTiming(date = date, repeat = ReminderRepeat.CUSTOM, intervalEvery = 2, intervalUnit = RecurrenceUnit.WEEKS)
        assertEquals(ReminderRepeat.CUSTOM, TaskChange.Timing(byDays).applyTo(base, zone)!!.repeat)
        assertEquals(RecurrenceUnit.WEEKS, TaskChange.Timing(byInterval).applyTo(base, zone)!!.intervalUnit)
    }

    @Test
    fun `personalizada sem data nao e repeticao, entao nao invalida`() {
        // Sem data a repetição efetiva é NONE: nada a validar.
        assertTrue(TaskTiming(date = null, repeat = ReminderRepeat.CUSTOM).isValid)
    }

    @Test
    fun `so o caso de data e hora mexe no lembrete`() {
        assertTrue(TaskChange.Timing(TaskTiming(date = null)).touchesReminder)
        listOf(
            TaskChange.Title("x"),
            TaskChange.Notes("x"),
            TaskChange.Location("x"),
            TaskChange.ShoppingList(1),
            TaskChange.NewShoppingList("Mercado"),
        ).forEach { assertFalse("$it", it.touchesReminder) }
    }

    @Test
    fun `lista existente vincula e null desvincula`() {
        assertEquals(base.copy(shoppingListId = 9), TaskChange.ShoppingList(9).applyTo(base, zone))
        assertEquals(base.copy(shoppingListId = null), TaskChange.ShoppingList(null).applyTo(base, zone))
    }

    @Test
    fun `lista nova com nome em branco e invalida`() {
        assertNull(TaskChange.NewShoppingList("  ").applyTo(base, zone))
        assertEquals(base, TaskChange.NewShoppingList("Mercado").applyTo(base, zone))
    }

    @Test
    fun `leitura tolerante - personalizada quebrada chega ao editor como nao repete`() {
        val broken = base.copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0, intervalUnit = null)
        assertEquals(ReminderRepeat.NONE, TaskTiming.of(broken, zone).repeat)
        val fine = base.copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0b1)
        assertEquals(ReminderRepeat.CUSTOM, TaskTiming.of(fine, zone).repeat)
    }

    @Test
    fun `leitura tolerante - intervalo 0 com unidade repete de verdade e continua repetindo`() {
        // A1 do Esquadro: o ReminderRecurrence conta o intervalo menor que 1 como 1.
        val zero = base.copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0, intervalEvery = 0, intervalUnit = RecurrenceUnit.DAYS)
        assertEquals(ReminderRepeat.CUSTOM, TaskTiming.of(zero, zone).repeat)
        // E gravar de novo normaliza o intervalo para 1, sem trocar a repetição.
        val rewritten = TaskChange.Timing(TaskTiming.of(zero, zone)).applyTo(zero, zone)!!
        assertEquals(ReminderRepeat.CUSTOM, rewritten.repeat)
        assertEquals(1, rewritten.intervalEvery)
    }

    /**
     * A1 do Esquadro: [CustomRecurrence.isUsable] é falso SE E SOMENTE SE a personalizada nunca
     * ocorre pelo [ReminderRecurrence]. Percorre as combinações de dias, intervalo e unidade e
     * procura alguma ocorrência em 400 dias a partir da âncora.
     */
    @Test
    fun `isUsable espelha o ReminderRecurrence`() {
        val anchor = LocalDate.of(2026, 1, 1).atTime(9, 0)
        val masks = listOf(0, 0b1, 0b101, RoutineDays.ALL, 0b1000_0000, 0b1000_0001, 0xFF00)
        val units = listOf<RecurrenceUnit?>(null) + RecurrenceUnit.entries
        val everys = listOf(-1, 0, 1, 3)
        for (mask in masks) for (unit in units) for (every in everys) {
            val occurs = (0L until 400L).any { offset ->
                ReminderRecurrence.occursOn(anchor.toLocalDate().plusDays(offset), anchor, ReminderRepeat.CUSTOM, every, unit, mask)
            }
            assertEquals("dias=$mask unidade=$unit a cada $every", occurs, CustomRecurrence.isUsable(unit, mask))
        }
    }
}
