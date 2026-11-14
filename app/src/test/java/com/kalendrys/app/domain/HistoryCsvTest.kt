package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryCsvTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 10, 24)

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private val routine = Routine(id = 1, title = "Treino", startMinute = 17 * 60 + 20, durationMinutes = 15, createdAt = 0L)

    private fun csv(rows: List<HistoryCsv.Row>) = StringBuilder().also { HistoryCsv.write(rows, it) }.toString()

    @Test
    fun `campos com separador aspas ou quebra de linha vao entre aspas`() {
        assertEquals("Ler", HistoryCsv.escape("Ler"))
        assertEquals("\"Pão; leite\"", HistoryCsv.escape("Pão; leite"))
        assertEquals("\"Livro \"\"Duna\"\"\"", HistoryCsv.escape("Livro \"Duna\""))
        assertEquals("\"linha 1\nlinha 2\"", HistoryCsv.escape("linha 1\nlinha 2"))
    }

    @Test
    fun `tarefas e rotinas em ordem com cabecalho e CRLF`() {
        val rows = HistoryCsv.rows(
            tasks = listOf(Task(id = 7, title = "Pagar contas; luz")),
            taskLogs = listOf(CompletionLog(taskId = 7, completedAt = millis(day.atTime(9, 5)))),
            routines = listOf(routine),
            routineLogs = listOf(RoutineCompletion(routineId = 1, epochDay = day.minusDays(1).toEpochDay(), completedAt = millis(day.minusDays(1).atTime(17, 40)))),
            zone = zone,
        )

        assertEquals(
            "Dia;Tipo;Título;Horário;Concluída em;Situação\r\n" +
                "2026-10-23;Rotina;Treino;17:20–17:35;2026-10-23 17:40;\r\n" +
                "2026-10-24;Tarefa;\"Pagar contas; luz\";;2026-10-24 09:05;\r\n",
            csv(rows),
        )
    }

    @Test
    fun `rotina marcada depois fica no dia da ocorrencia`() {
        val late = HistoryCsv.rows(
            tasks = emptyList(),
            taskLogs = emptyList(),
            routines = listOf(routine),
            routineLogs = listOf(RoutineCompletion(routineId = 1, epochDay = day.minusDays(2).toEpochDay(), completedAt = millis(day.atTime(8, 0)))),
            zone = zone,
        ).single()

        assertEquals(day.minusDays(2), late.day)
        assertEquals(day.atTime(8, 0), late.completedAt)
    }

    @Test
    fun `tarefa excluida sai sem titulo e rotina arquivada mantem o titulo`() {
        val rows = HistoryCsv.rows(
            tasks = emptyList(),
            taskLogs = listOf(CompletionLog(taskId = 99, completedAt = millis(day.atTime(10, 0)))),
            routines = listOf(routine.copy(archivedAt = millis(day.atTime(12, 0)), durationMinutes = 0)),
            routineLogs = listOf(RoutineCompletion(routineId = 1, epochDay = day.toEpochDay(), completedAt = millis(day.atTime(17, 30)))),
            zone = zone,
        )

        assertEquals(
            "Dia;Tipo;Título;Horário;Concluída em;Situação\r\n" +
                "2026-10-24;Tarefa;;;2026-10-24 10:00;Excluída\r\n" +
                "2026-10-24;Rotina;Treino;17:20;2026-10-24 17:30;Excluída\r\n",
            csv(rows),
        )
    }

    @Test
    fun `BOM e exatamente o caractere U+FEFF`() {
        assertEquals(1, HistoryCsv.BOM.length)
        assertEquals(0xFEFF, HistoryCsv.BOM[0].code)
    }
}
