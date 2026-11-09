package com.kalendrys.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
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
        sourceDb.routineDao().insert(
            Routine(id = 5, title = "Treino", startMinute = 1040, durationMinutes = 15, remind = true, archivedAt = 999),
        )
        sourceDb.routineCompletionDao().insert(RoutineCompletion(id = 7, routineId = 5, epochDay = 20_000, completedAt = 333))

        val (json, stats) = BackupManager(sourceDb, context.contentResolver, source).serialize()
        assertEquals(BackupManager.Stats(tasks = 4, completionLogs = 2, routines = 1, routineCompletions = 1), stats)

        // Destino com dado "sujo" que o restauro deve apagar antes de reinserir.
        val dest = settingsRepo("dest")
        destDb.taskDao().insert(Task(id = 99, title = "Lixo a ser apagado"))
        val restoredStats = BackupManager(destDb, context.contentResolver, dest).restore(json)

        assertEquals(stats, restoredStats)
        assertEquals(sourceDb.taskDao().all().sortedBy { it.id }, destDb.taskDao().all().sortedBy { it.id })
        assertEquals(sourceDb.completionLogDao().all(), destDb.completionLogDao().all())
        assertEquals(sourceDb.routineDao().all(), destDb.routineDao().all())
        assertEquals(sourceDb.routineCompletionDao().all(), destDb.routineCompletionDao().all())

        val restoredSettings = dest.settings.first()
        assertEquals(false, restoredSettings.summaryEnabled)
        assertEquals(LocalTime.of(21, 15), restoredSettings.summaryTime)
    }

    @Test
    fun `restaura backup v1 sem os campos custom caindo nos defaults`() = runTest {
        val source = settingsRepo("source-v1")
        sourceDb.taskDao().insert(
            Task(id = 1, title = "Antiga", dueDate = 20_000, reminderAt = 123, repeat = ReminderRepeat.CUSTOM, intervalEvery = 5, intervalUnit = RecurrenceUnit.WEEKS, byWeekdays = 0b1),
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
        // O resto da tarefa é preservado.
        assertEquals("Antiga", restored.title)
        assertEquals(ReminderRepeat.CUSTOM, restored.repeat)
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
