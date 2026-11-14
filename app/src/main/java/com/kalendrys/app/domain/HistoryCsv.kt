package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Histórico de conclusões (tarefas e rotinas) em CSV, no formato que o Excel em português abre
 * direto: separador ";" (a vírgula é o separador decimal), linhas com CRLF e o arquivo começando
 * com o BOM do UTF-8 — sem ele o Excel lê os acentos errado. O Google Planilhas aceita igual.
 */
object HistoryCsv {

    // Escape em vez do caractere cru: U+FEFF invisível no meio do fonte é erro de lint (ByteOrderMark).
    const val BOM = "\uFEFF"
    const val SEPARATOR = ';'
    private const val LINE_END = "\r\n"

    val HEADER = listOf("Dia", "Tipo", "Título", "Horário", "Concluída em", "Situação")

    enum class Type(val label: String) {
        TASK("Tarefa"),
        ROUTINE("Rotina"),
    }

    data class Row(
        /** Dia da conclusão (tarefa) ou da ocorrência do bloco (rotina, que pode ser marcada depois). */
        val day: LocalDate,
        val type: Type,
        /** Vazio para uma tarefa excluída: o registro de conclusão não guarda o título. */
        val title: String,
        /** Horário do bloco de rotina ("17:20–17:35"); vazio para tarefas. */
        val blockTime: String,
        val completedAt: LocalDateTime,
        val deleted: Boolean,
    )

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Uma linha por conclusão, em ordem de dia e de horário em que foi marcada. */
    fun rows(
        tasks: List<Task>,
        taskLogs: List<CompletionLog>,
        routines: List<Routine>,
        routineLogs: List<RoutineCompletion>,
        zone: ZoneId,
    ): List<Row> {
        val tasksById = tasks.associateBy { it.id }
        val routinesById = routines.associateBy { it.id }
        val taskRows = taskLogs.map { log ->
            val task = tasksById[log.taskId]
            val completedAt = dateTime(log.completedAt, zone)
            Row(
                day = completedAt.toLocalDate(),
                type = Type.TASK,
                title = task?.title.orEmpty(),
                blockTime = "",
                completedAt = completedAt,
                deleted = task == null,
            )
        }
        val routineRows = routineLogs.map { log ->
            val routine = routinesById[log.routineId]
            Row(
                day = LocalDate.ofEpochDay(log.epochDay),
                type = Type.ROUTINE,
                title = routine?.title.orEmpty(),
                blockTime = routine?.let(::blockTime).orEmpty(),
                completedAt = dateTime(log.completedAt, zone),
                deleted = routine == null || routine.archivedAt != null,
            )
        }
        return (taskRows + routineRows).sortedWith(compareBy({ it.day }, { it.completedAt }))
    }

    fun write(rows: List<Row>, out: Appendable) {
        out.appendLine(HEADER)
        rows.forEach { row ->
            out.appendLine(
                listOf(
                    row.day.format(DAY),
                    row.type.label,
                    row.title,
                    row.blockTime,
                    row.completedAt.format(DATE_TIME),
                    if (row.deleted) "Excluída" else "",
                ),
            )
        }
    }

    /** Entre aspas só quando precisa (separador, aspas ou quebra de linha), com as aspas internas dobradas. */
    fun escape(field: String): String =
        if (field.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun Appendable.appendLine(fields: List<String>) {
        append(fields.joinToString(SEPARATOR.toString(), transform = ::escape))
        append(LINE_END)
    }

    private fun blockTime(routine: Routine): String {
        val start = timeOf(routine.startMinute)
        return if (routine.durationMinutes == 0) start else "$start–${timeOf(routine.endMinute)}"
    }

    private fun timeOf(minute: Int): String = LocalTime.of(minute % 1440 / 60, minute % 60).format(TIME)

    private fun dateTime(epochMillis: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
}
