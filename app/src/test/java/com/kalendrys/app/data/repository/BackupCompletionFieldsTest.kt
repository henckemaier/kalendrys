package com.kalendrys.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Banco 11 (v1.8.0, E0): o registro de conclusão guarda o que uma conclusão adiantada sobrescreveu.
 * No backup os três campos são OPCIONAIS no mesmo schema 6: escritos só quando preenchidos, e um
 * backup sem eles (v1.7.1 ou anterior) restaura com eles vazios.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupCompletionFieldsTest {

    private lateinit var context: Context
    private lateinit var sourceDb: AppDatabase
    private lateinit var destDb: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sourceDb = db()
        destDb = db()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        destDb.close()
    }

    private fun db() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    private fun settings(name: String) = SettingsRepository(
        PreferenceDataStoreFactory.create { File(context.cacheDir, "completion-$name-${System.nanoTime()}.preferences_pb") },
    )

    private val early = CompletionLog(
        id = 1, taskId = 1, completedAt = 1_000,
        previousDueDate = 20_405, previousReminderAt = 1_763_100_000_000, rewrittenDueDate = 20_400,
    )
    private val plain = CompletionLog(id = 2, taskId = 2, completedAt = 2_000)

    @Test
    fun `ida e volta preserva os campos da conclusao adiantada e so os escreve quando preenchidos`() = runTest {
        sourceDb.taskDao().insert(Task(id = 1, title = "Adiantada", dueDate = 20_400))
        sourceDb.taskDao().insert(Task(id = 2, title = "Comum"))
        sourceDb.completionLogDao().insertAll(listOf(early, plain))

        val (json, _) = BackupManager(sourceDb, context.contentResolver, settings("src")).serialize()
        val root = JSONObject(json)
        assertEquals(6, root.getInt("schemaVersion"))
        assertEquals(11, root.getInt("databaseVersion"))
        val logs = root.getJSONArray("completionLogs")
        val earlyJson = (0 until logs.length()).map { logs.getJSONObject(it) }.single { it.getLong("id") == 1L }
        val plainJson = (0 until logs.length()).map { logs.getJSONObject(it) }.single { it.getLong("id") == 2L }
        assertEquals(20_405L, earlyJson.getLong("previousDueDate"))
        assertFalse(plainJson.has("previousDueDate"))
        assertFalse(plainJson.has("previousReminderAt"))
        assertFalse(plainJson.has("rewrittenDueDate"))

        BackupManager(destDb, context.contentResolver, settings("dst")).restore(json)

        assertEquals(listOf(early, plain), destDb.completionLogDao().all().sortedBy { it.id })
    }

    @Test
    fun `backup da v1_7_1 sem os campos restaura com eles vazios`() = runTest {
        // Formato da v1.7.1 (schema 6, banco 10): registro de conclusão só com id, taskId e completedAt.
        val json = JSONObject()
            .put("schemaVersion", 6)
            .put("databaseVersion", 10)
            .put("appVersion", "1.7.1")
            .put("tasks", JSONArray().put(JSONObject().put("id", 1).put("title", "T").put("createdAt", 1).put("repeat", "NONE")))
            .put("completionLogs", JSONArray().put(JSONObject().put("id", 7).put("taskId", 1).put("completedAt", 5_000)))
            .put("routines", JSONArray())
            .put("routineCompletions", JSONArray())
            .put("shoppingLists", JSONArray())
            .put("shoppingItems", JSONArray())
            .toString()

        BackupManager(destDb, context.contentResolver, settings("v171")).restore(json)

        val log = destDb.completionLogDao().all().single()
        assertEquals(CompletionLog(id = 7, taskId = 1, completedAt = 5_000), log)
        assertNull(log.previousDueDate)
        assertNull(log.previousReminderAt)
        assertNull(log.rewrittenDueDate)
    }

    @Test
    fun `registro sem os campos no meio de outros com os campos`() = runTest {
        val third = early.copy(id = 3, taskId = 3, previousReminderAt = null)
        sourceDb.taskDao().insert(Task(id = 1, title = "A"))
        sourceDb.taskDao().insert(Task(id = 2, title = "B"))
        sourceDb.taskDao().insert(Task(id = 3, title = "C"))
        sourceDb.completionLogDao().insertAll(listOf(early, plain, third))

        val (json, _) = BackupManager(sourceDb, context.contentResolver, settings("mix-src")).serialize()
        BackupManager(destDb, context.contentResolver, settings("mix-dst")).restore(json)

        val restored = destDb.completionLogDao().all().sortedBy { it.id }
        assertEquals(listOf(early, plain, third), restored)
        assertTrue(restored[2].previousReminderAt == null && restored[2].previousDueDate == 20_405L)
    }
}
