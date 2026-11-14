package com.kalendrys.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O arquivo CSV exportado byte a byte (v1.5.1 trocou o BOM cru por escape): começa com o BOM do
 * UTF-8 (EF BB BF) uma única vez, seguido do conteúdo no formato estável, independente do idioma do
 * aparelho, e sobrescreve por inteiro um arquivo já existente.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryExporterTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2026, 10, 24)

    @Before
    fun setup() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousZone)
    }

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private val expectedContent =
        "Dia;Tipo;Título;Horário;Concluída em;Situação\r\n" +
            "2026-10-23;Rotina;Treino;17:20–17:35;2026-10-23 17:40;\r\n" +
            "2026-10-24;Tarefa;\"Pagar contas; luz\";;2026-10-24 09:05;\r\n" +
            "2026-10-24;Tarefa;;;2026-10-24 10:00;Excluída\r\n"

    private suspend fun seed() {
        db.taskDao().insert(Task(id = 7, title = "Pagar contas; luz"))
        db.completionLogDao().insert(CompletionLog(taskId = 7, completedAt = millis(day.atTime(9, 5))))
        db.completionLogDao().insert(CompletionLog(taskId = 99, completedAt = millis(day.atTime(10, 0)))) // tarefa excluída
        db.routineDao().insert(Routine(id = 1, title = "Treino", startMinute = 17 * 60 + 20, durationMinutes = 15, createdAt = 0L))
        db.routineCompletionDao().insert(
            RoutineCompletion(routineId = 1, epochDay = day.minusDays(1).toEpochDay(), completedAt = millis(day.minusDays(1).atTime(17, 40))),
        )
    }

    private fun tempFile(name: String) = File(context.cacheDir, "history-$name-${System.nanoTime()}.csv")

    @Test
    fun `arquivo exportado tem BOM unico e conteudo identico byte a byte`() = runTest {
        seed()
        val file = tempFile("bytes")

        val written = HistoryExporter(db, context.contentResolver).exportTo(Uri.fromFile(file))

        assertEquals(3, written)
        val bytes = file.readBytes()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertArrayEquals(bom, bytes.copyOfRange(0, 3))
        // Só um BOM: o 4º byte já é o "D" do cabeçalho.
        assertEquals('D'.code.toByte(), bytes[3])
        assertArrayEquals(bom + expectedContent.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `exportar por cima de arquivo existente sobrescreve por inteiro`() = runTest {
        seed()
        val file = tempFile("overwrite").apply { writeText("x".repeat(10_000)) }

        HistoryExporter(db, context.contentResolver).exportTo(Uri.fromFile(file))

        assertEquals("﻿" + expectedContent, file.readText(Charsets.UTF_8))
    }
}
