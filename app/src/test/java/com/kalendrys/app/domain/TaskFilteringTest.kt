package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFilteringTest {

    private fun task(title: String = "", notes: String = "", location: String = "") =
        Task(id = 1, title = title, notes = notes, location = location)

    @Test
    fun `consulta vazia ou em branco casa qualquer tarefa`() {
        val t = task(title = "Comprar pão")
        assertTrue(TaskFiltering.matches(t, ""))
        assertTrue(TaskFiltering.matches(t, "   "))
    }

    @Test
    fun `casa substring no titulo sem diferenciar maiusculas`() {
        val t = task(title = "Comprar pão")
        assertTrue(TaskFiltering.matches(t, "pão"))
        assertTrue(TaskFiltering.matches(t, "COMPRAR"))
        assertTrue(TaskFiltering.matches(t, "rar pã"))
        assertFalse(TaskFiltering.matches(t, "leite"))
    }

    @Test
    fun `casa tambem nas observacoes e no local`() {
        val t = task(title = "Reunião", notes = "Levar o relatório", location = "Sala 3")
        assertTrue(TaskFiltering.matches(t, "relatório"))
        assertTrue(TaskFiltering.matches(t, "sala"))
        assertFalse(TaskFiltering.matches(t, "cozinha"))
    }

    @Test
    fun `espacos nas pontas da consulta sao ignorados`() {
        assertTrue(TaskFiltering.matches(task(title = "Pagar conta"), "  conta  "))
    }
}
