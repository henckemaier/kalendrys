package com.kalendrys.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round-trip do [BackupManager]: exportar (serialize) e importar (restore) num banco limpo tem
 * que devolver exatamente os mesmos dados, com ids preservados, e substituir o que havia antes.
 * Exercita diretamente serialize/restore para não depender do ContentResolver/SAF.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var sourceDb: AppDatabase
    private lateinit var destDb: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sourceDb = inMemoryDb()
        destDb = inMemoryDb()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        destDb.close()
    }

    @Test
    fun `exportar e importar devolve os mesmos dados`() = runTest {
        val source = settingsRepo("source")
        source.setSummaryEnabled(false)
        source.setSummaryTime(LocalTime.of(21, 15))

        // Banco de origem com ids explícitos e relações tarefa↔log e rotina↔conclusão.
        sourceDb.taskDao().insert(
            Task(id = 1, title = "Comprar pão", notes = "integral", dueDate = 20_000, reminderAt = 123_456_789, repeat = ReminderRepeat.DAILY, location = "Padaria"),
        )
        sourceDb.taskDao().insert(Task(id = 2, title = "Sem data"))
        // Recorrência custom: os campos novos (intervalEvery/intervalUnit/byWeekdays) têm que sobreviver ao round-trip.
        sourceDb.taskDao().insert(
            Task(id = 3, title = "A cada 3 dias", dueDate = 20_000, reminderAt = 555, repeat = ReminderRepeat.CUSTOM, intervalEvery = 3, intervalUnit = RecurrenceUnit.DAYS),
        )
        sourceDb.taskDao().insert(
            Task(id = 4, title = "Seg/qua/sex", dueDate = 20_000, reminderAt = 666, repeat = ReminderRepeat.CUSTOM, byWeekdays = 0b0010101),
        )
        sourceDb.completionLogDao().insert(CompletionLog(id = 1, taskId = 1, completedAt = 111))
        sourceDb.completionLogDao().insert(CompletionLog(id = 2, taskId = 1, completedAt = 222))
        // Categorias de rotina (v7): precedem a rotina que as referencia por categoryId.
        sourceDb.routineCategoryDao().insertAll(
            listOf(
                RoutineCategory(id = 10, name = "Academia", color = "RED", kind = "SIMPLE"),
                RoutineCategory(id = 11, name = "Mercado", color = "GREEN", kind = "SIMPLE"),
            ),
        )
        // Rotina com os campos novos da v7 preenchidos (têm que sobreviver ao round-trip).
        sourceDb.routineDao().insert(
            Routine(
                id = 5, title = "Treino", startMinute = 1040, durationMinutes = 15, remind = true, archivedAt = 999,
                categoryId = 10, pausedAt = 4242, weekInterval = 2, startEpochDay = 19_000, endEpochDay = 21_000,
            ),
        )
        sourceDb.routineCompletionDao().insert(RoutineCompletion(id = 7, routineId = 5, epochDay = 20_000, completedAt = 333))
        // Exceções de rotina (v7): um SKIP e um RESCHEDULE com os campos opcionais.
        sourceDb.routineOverrideDao().insertAll(
            listOf(
                RoutineOverride(id = 20, routineId = 5, epochDay = 20_010, type = "SKIP"),
                RoutineOverride(
                    id = 21, routineId = 5, epochDay = 20_011, type = "RESCHEDULE",
                    newStartMinute = 600, newEpochDay = 20_012, createdAt = 444,
                ),
            ),
        )

        val (json, stats) = BackupManager(sourceDb, context.contentResolver, source).serialize()
        assertEquals(
            BackupManager.Stats(
                tasks = 4, completionLogs = 2, routines = 1, routineCompletions = 1,
                routineCategories = 2, routineOverrides = 2,
            ),
            stats,
        )

        // Destino com dado "sujo" que o restauro deve apagar antes de reinserir.
        val dest = settingsRepo("dest")
        destDb.taskDao().insert(Task(id = 99, title = "Lixo a ser apagado"))
        val restoredStats = BackupManager(destDb, context.contentResolver, dest).restore(json)

        assertEquals(stats, restoredStats)
        assertEquals(sourceDb.taskDao().all().sortedBy { it.id }, destDb.taskDao().all().sortedBy { it.id })
        assertEquals(sourceDb.completionLogDao().all(), destDb.completionLogDao().all())
        assertEquals(sourceDb.routineDao().all(), destDb.routineDao().all())
        assertEquals(sourceDb.routineCompletionDao().all(), destDb.routineCompletionDao().all())
        assertEquals(sourceDb.routineCategoryDao().all(), destDb.routineCategoryDao().all())
        assertEquals(sourceDb.routineOverrideDao().all(), destDb.routineOverrideDao().all())

        val restoredSettings = dest.settings.first()
        assertEquals(false, restoredSettings.summaryEnabled)
        assertEquals(LocalTime.of(21, 15), restoredSettings.summaryTime)
    }

    @Test
    fun `restaura backup v1 sem os campos custom caindo nos defaults`() = runTest {
        val source = settingsRepo("source-v1")
        // v1 só tinha repetições simples (sem CUSTOM); DAILY é o caso realista de um backup antigo.
        sourceDb.taskDao().insert(
            Task(id = 1, title = "Antiga", dueDate = 20_000, reminderAt = 123, repeat = ReminderRepeat.DAILY),
        )
        val (json, _) = BackupManager(sourceDb, context.contentResolver, source).serialize()

        // Simula um backup do formato v1: marca schemaVersion=1 e remove os campos custom das tarefas.
        val root = JSONObject(json).put("schemaVersion", 1)
        val tasks = root.getJSONArray("tasks")
        for (i in 0 until tasks.length()) {
            tasks.getJSONObject(i).apply {
                remove("intervalEvery"); remove("intervalUnit"); remove("byWeekdays")
            }
        }

        val dest = settingsRepo("dest-v1")
        BackupManager(destDb, context.contentResolver, dest).restore(root.toString())

        val restored = destDb.taskDao().all().single()
        // Campos ausentes caem nos defaults, sem quebrar o restauro (compat com o T5).
        assertEquals(1, restored.intervalEvery)
        assertNull(restored.intervalUnit)
        assertEquals(0, restored.byWeekdays)
        // O resto da tarefa é preservado (a repetição simples continua intacta).
        assertEquals("Antiga", restored.title)
        assertEquals(ReminderRepeat.DAILY, restored.repeat)
    }

    @Test
    fun `restaura CUSTOM sem config utilizavel normalizando para NONE`() = runTest {
        val source = settingsRepo("source-inert")
        sourceDb.taskDao().insert(
            Task(id = 1, title = "Inerte", dueDate = 20_000, reminderAt = 1, repeat = ReminderRepeat.CUSTOM, intervalEvery = 3, intervalUnit = RecurrenceUnit.DAYS),
        )
        val (json, _) = BackupManager(sourceDb, context.contentResolver, source).serialize()

        // Corrompe o JSON: CUSTOM sem unidade e sem dias-da-semana (tarefa que nunca ocorreria).
        val root = JSONObject(json)
        val tasks = root.getJSONArray("tasks")
        for (i in 0 until tasks.length()) {
            tasks.getJSONObject(i).apply { remove("intervalUnit"); put("byWeekdays", 0) }
        }

        val dest = settingsRepo("dest-inert")
        BackupManager(destDb, context.contentResolver, dest).restore(root.toString())

        // Normalizado para NONE em vez de restaurar uma tarefa silenciosamente inerte.
        assertEquals(ReminderRepeat.NONE, destDb.taskDao().all().single().repeat)
    }

    @Test
    fun `restaura backup v2 sem os campos e tabelas de rotina caindo nos defaults e vazio`() = runTest {
        val source = settingsRepo("source-v2")
        sourceDb.routineDao().insert(
            Routine(id = 1, title = "Antiga", startMinute = 600, durationMinutes = 30, remind = true),
        )
        val (json, _) = BackupManager(sourceDb, context.contentResolver, source).serialize()

        // Simula um backup do formato v2: schemaVersion=2, sem as arrays novas e sem os campos novos
        // de routines (categoryId/pausedAt/weekInterval/startEpochDay/endEpochDay).
        val root = JSONObject(json).put("schemaVersion", 2)
        root.remove("routineCategories")
        root.remove("routineOverrides")
        val routines = root.getJSONArray("routines")
        for (i in 0 until routines.length()) {
            routines.getJSONObject(i).apply {
                remove("categoryId"); remove("pausedAt"); remove("weekInterval")
                remove("startEpochDay"); remove("endEpochDay")
            }
        }

        val dest = settingsRepo("dest-v2")
        BackupManager(destDb, context.contentResolver, dest).restore(root.toString())

        val restored = destDb.routineDao().all().single()
        assertEquals("Antiga", restored.title)
        // Campos novos ausentes caem nos defaults (sem categoria/pausa/vigência, intervalo semanal 1).
        assertNull(restored.categoryId)
        assertNull(restored.pausedAt)
        assertEquals(1, restored.weekInterval)
        assertNull(restored.startEpochDay)
        assertNull(restored.endEpochDay)
        // As tabelas novas ficam vazias, sem quebrar o restauro de um backup anterior à v3.
        assertTrue(destDb.routineCategoryDao().all().isEmpty())
        assertTrue(destDb.routineOverrideDao().all().isEmpty())
    }

    private fun inMemoryDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun settingsRepo(name: String): SettingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "backup-test-$name-${System.nanoTime()}.preferences_pb")
            },
        )
}
