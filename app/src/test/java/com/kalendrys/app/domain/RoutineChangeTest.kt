package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Routine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cada [RoutineChange] aplica só o seu campo e recusa o estado inválido (ADR-0002, 5.3 e 15.2). */
class RoutineChangeTest {

    private val base = Routine(
        id = 4,
        title = "Treino",
        startMinute = 17 * 60 + 20,
        durationMinutes = 15,
        daysOfWeek = 0b0011111,
        pausedAt = 123L,
        startEpochDay = 100,
        endEpochDay = 200,
        shoppingListId = 2,
    )

    @Test
    fun `titulo aparado, vazio invalido`() {
        assertEquals(base.copy(title = "Corrida"), RoutineChange.Title(" Corrida ").applyTo(base))
        assertNull(RoutineChange.Title(" ").applyTo(base))
    }

    @Test
    fun `inicio e duracao dentro do dia`() {
        assertEquals(base.copy(startMinute = 0), RoutineChange.Start(0).applyTo(base))
        assertEquals(base.copy(startMinute = 1439), RoutineChange.Start(1439).applyTo(base))
        assertNull(RoutineChange.Start(1440).applyTo(base))
        assertNull(RoutineChange.Start(-1).applyTo(base))
        assertEquals(base.copy(durationMinutes = 0), RoutineChange.Duration(0).applyTo(base))
        assertNull(RoutineChange.Duration(1440).applyTo(base))
        assertNull(RoutineChange.Duration(-5).applyTo(base))
    }

    @Test
    fun `sem nenhum dia e invalido e bits de fora sao ignorados`() {
        assertNull(RoutineChange.Days(0).applyTo(base))
        assertNull(RoutineChange.Days(0b1000_0000).applyTo(base))
        assertEquals(base.copy(daysOfWeek = 0b1), RoutineChange.Days(0b1000_0001).applyTo(base))
    }

    @Test
    fun `intervalo de semanas de 1 a 12`() {
        assertEquals(base.copy(weekInterval = 12), RoutineChange.WeekInterval(12).applyTo(base))
        assertNull(RoutineChange.WeekInterval(0).applyTo(base))
        assertNull(RoutineChange.WeekInterval(13).applyTo(base))
    }

    @Test
    fun `mover o inicio da vigencia para depois do fim limpa o fim`() {
        assertEquals(base.copy(startEpochDay = 250, endEpochDay = null), RoutineChange.ValidFrom(250).applyTo(base))
        assertEquals(base.copy(startEpochDay = 150), RoutineChange.ValidFrom(150).applyTo(base))
        assertEquals(base.copy(startEpochDay = null), RoutineChange.ValidFrom(null).applyTo(base))
    }

    @Test
    fun `fim da vigencia antes do inicio e invalido`() {
        assertNull(RoutineChange.ValidUntil(50).applyTo(base))
        assertEquals(base.copy(endEpochDay = 100), RoutineChange.ValidUntil(100).applyTo(base))
        assertEquals(base.copy(endEpochDay = null), RoutineChange.ValidUntil(null).applyTo(base))
    }

    @Test
    fun `aparencia, categoria e lista nao mexem no resto`() {
        assertEquals(base.copy(color = RoutineColor.entries.last().name), RoutineChange.Color(RoutineColor.entries.last()).applyTo(base))
        assertEquals(base.copy(icon = RoutineIcon.entries.last().name), RoutineChange.Icon(RoutineIcon.entries.last()).applyTo(base))
        assertEquals(base.copy(categoryId = 5), RoutineChange.Category(5).applyTo(base))
        assertEquals(base.copy(shoppingListId = null), RoutineChange.ShoppingList(null).applyTo(base))
        assertNull(RoutineChange.NewShoppingList("").applyTo(base))
    }

    @Test
    fun `nenhuma mudanca mexe na pausa`() {
        val changes = listOf(
            RoutineChange.Title("x"), RoutineChange.Start(60), RoutineChange.Days(1), RoutineChange.Remind(false),
            RoutineChange.ValidFrom(10), RoutineChange.Color(RoutineColor.DEFAULT),
        )
        changes.forEach { assertEquals("$it", 123L, it.applyTo(base)!!.pausedAt) }
    }

    @Test
    fun `so inicio, dias, intervalo, vigencia e lembrar mexem no alarme`() {
        listOf(
            RoutineChange.Start(1), RoutineChange.Days(1), RoutineChange.WeekInterval(2),
            RoutineChange.ValidFrom(1), RoutineChange.ValidUntil(null), RoutineChange.Remind(true),
        ).forEach { assertTrue("$it", it.touchesSchedule) }
        listOf(
            RoutineChange.Title("x"), RoutineChange.Notes("x"), RoutineChange.Duration(5),
            RoutineChange.Category(null), RoutineChange.Color(RoutineColor.DEFAULT), RoutineChange.Icon(RoutineIcon.DEFAULT),
            RoutineChange.ShoppingList(null), RoutineChange.NewShoppingList("x"),
        ).forEach { assertFalse("$it", it.touchesSchedule) }
    }
}
