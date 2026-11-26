package com.kalendrys.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.RoutineKind
import com.kalendrys.app.ui.routineeditor.RoutineDraft
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Restauro de backup v7 ADULTERADO (v1.10.0, ADR-0002 16.10.3; caso extra do Esquadro): um v7 que não
 * saiu do app (editado à mão, ou de um build com defeito) passa pelas mesmas regras do [ListOwnership]
 * e nunca grava duas tarefas na mesma lista (o índice único derrubaria o restauro inteiro). Também: o
 * tipo do v7 não é recalculado pela categoria, e o backup ≤ 6 calcula.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupV7TamperedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withBackup(block: suspend (AppDatabase, BackupManager) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val settings = SettingsRepository(
                PreferenceDataStoreFactory.create { File(context.cacheDir, "v7-adulterado-${System.nanoTime()}.preferences_pb") },
            )
            block(db, BackupManager(db, context.contentResolver, settings, clock = SystemAppClock))
        } finally {
            db.close()
        }
    }

    private fun task(id: Long, createdAt: Long, listId: Long?) = JSONObject()
        .put("id", id).put("title", "t$id").put("notes", "").put("createdAt", createdAt).put("repeat", "NONE")
        .put("shoppingListId", listId ?: JSONObject.NULL)

    private fun routine(id: Long, categoryId: Long?, kind: String?, listId: Long? = null) = JSONObject()
        .put("id", id).put("title", "r$id").put("notes", "").put("icon", "DEFAULT").put("color", "DEFAULT")
        .put("startMinute", 600).put("durationMinutes", 30).put("daysOfWeek", 127).put("remind", true).put("createdAt", 0)
        .put("categoryId", categoryId ?: JSONObject.NULL)
        .apply { if (kind != null) put("kind", kind) }
        .apply { if (listId != null) put("shoppingListId", listId) }

    private fun list(id: Long, resetKey: String? = null) = JSONObject()
        .put("id", id).put("name", "Lista $id").put("createdAt", 7).put("lastResetKey", resetKey ?: JSONObject.NULL)

    private fun item(id: Long, listId: Long, position: Int = 0) = JSONObject()
        .put("id", id).put("listId", listId).put("name", "i$id").put("quantity", 1).put("priceCents", JSONObject.NULL)
        .put("picked", true).put("position", position).put("createdAt", 5).put("pickedBeforeReset", true)

    private fun backup(
        version: Int,
        tasks: List<JSONObject>,
        routines: List<JSONObject> = emptyList(),
        lists: List<JSONObject> = emptyList(),
        items: List<JSONObject> = emptyList(),
        saved: List<JSONObject>? = null,
    ): String = JSONObject()
        .put("schemaVersion", version)
        .put("databaseVersion", if (version >= 7) 12 else 11)
        .put("tasks", JSONArray(tasks))
        .put("completionLogs", JSONArray())
        .put("routines", JSONArray(routines))
        .put("routineCompletions", JSONArray())
        .put(
            "routineCategories",
            JSONArray(listOf(JSONObject().put("id", 1).put("name", "Academia").put("color", "DEFAULT").put("kind", "EXERCICIO"))),
        )
        .put("routineOverrides", JSONArray())
        .put("shoppingLists", JSONArray(lists))
        .put("shoppingItems", JSONArray(items))
        .put("exerciseItems", JSONArray())
        .apply { if (saved != null) put("savedExercises", JSONArray(saved)) }
        .toString()

    private suspend fun AppDatabase.links(): Map<Long, Long?> = taskDao().all().associate { it.id to it.shoppingListId }

    @Test
    fun `v7 com duas tarefas na mesma lista restaura com copia, sem derrubar o restauro`() = withBackup { db, backup ->
        val stats = backup.restore(
            backup(
                version = 7,
                tasks = listOf(task(1, 50, 1), task(2, 20, 1), task(3, 0, null)),
                lists = listOf(list(1, resetKey = "task-log:9")),
                items = listOf(item(10, 1, 1), item(11, 1, 0)),
                saved = emptyList(),
            ),
        )

        assertEquals(mapOf(1L to 2L, 2L to 1L, 3L to null), db.links())
        assertEquals(2, stats.shoppingLists)
        val copy = db.shoppingListDao().byId(2)!!
        assertNull("a cópia nasce sem reinício pendente", copy.lastResetKey)
        val copied = db.shoppingItemDao().all().filter { it.listId == 2L }
        assertEquals(listOf("i11", "i10"), copied.sortedBy { it.position }.map { it.name })
        assertTrue(copied.none { it.pickedBeforeReset })
        // O banco segue recusando duas na mesma (o índice único veio inteiro do restauro).
        try {
            db.taskDao().updateShoppingList(3, 1)
            fail("o índice único deveria recusar")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
    }

    @Test
    fun `v7 com tres tarefas de createdAt igual na mesma lista - fica a de menor id`() = withBackup { db, backup ->
        backup.restore(
            backup(
                version = 7,
                tasks = listOf(task(8, 100, 4), task(3, 100, 4), task(6, 100, 4)),
                lists = listOf(list(4)),
                items = listOf(item(40, 4)),
            ),
        )
        assertEquals(mapOf(3L to 4L, 6L to 5L, 8L to 6L), db.links())
    }

    @Test
    fun `v7 com rotina apontando para lista - o vinculo sai e a lista sem tarefa vai junto`() = withBackup { db, backup ->
        backup.restore(
            backup(
                version = 7,
                tasks = listOf(task(1, 0, 404), task(2, 0, 2)),
                routines = listOf(routine(1, null, "NONE", listId = 1)),
                lists = listOf(list(1), list(2)),
                items = listOf(item(10, 1), item(20, 2), item(30, 77)),
            ),
        )
        assertNull(db.routineDao().byId(1)!!.shoppingListId)
        assertEquals(mapOf(1L to null, 2L to 2L), db.links())
        assertEquals(listOf(2L), db.shoppingListDao().all().map { it.id })
        assertEquals(listOf(20L), db.shoppingItemDao().all().map { it.id })
    }

    @Test
    fun `v7 guarda o tipo gravado sem recalcular pela categoria, e tipo desconhecido le como Nenhum`() = withBackup { db, backup ->
        backup.restore(
            backup(
                version = 7,
                tasks = emptyList(),
                routines = listOf(routine(1, categoryId = 1, kind = "NONE"), routine(2, categoryId = null, kind = "EXERCISE"), routine(3, null, "FOO")),
            ),
        )
        assertEquals("NONE", db.routineDao().byId(1)!!.kind)
        assertEquals("EXERCISE", db.routineDao().byId(2)!!.kind)
        // Valor estranho entra como veio; a leitura (RoutineKind.fromKey) cai no padrão, sem quebrar.
        assertEquals(RoutineKind.NONE, RoutineDraft.from(db.routineDao().byId(3)!!).kind)
    }

    @Test
    fun `v6 sem o tipo calcula pela categoria, como a migracao`() = withBackup { db, backup ->
        backup.restore(
            backup(
                version = 6,
                tasks = emptyList(),
                routines = listOf(routine(1, categoryId = 1, kind = null), routine(2, categoryId = 404, kind = null), routine(3, null, null)),
            ),
        )
        assertEquals(listOf("EXERCISE", "NONE", "NONE"), db.routineDao().all().sortedBy { it.id }.map { it.kind })
        assertTrue(db.savedExerciseDao().all().isEmpty())
    }

    @Test
    fun `v7 com exercicios salvos restaura com os mesmos ids e ida e volta nao muda`() = withBackup { db, backup ->
        val saved = listOf(
            JSONObject().put("id", 5).put("name", "Agachamento").put("sets", 4).put("reps", 8).put("load", JSONObject.NULL)
                .put("restSeconds", 90).put("createdAt", 11),
            JSONObject().put("id", 9).put("name", "Remada").put("sets", 3).put("reps", 12).put("load", "30kg")
                .put("restSeconds", JSONObject.NULL).put("createdAt", 12),
        )
        val stats = backup.restore(backup(version = 7, tasks = emptyList(), saved = saved))
        assertEquals(2, stats.savedExercises)
        val before = db.savedExerciseDao().all()
        assertEquals(listOf(5L, 9L), before.map { it.id })
        assertEquals("30kg", before[1].load)

        val (json, _) = backup.serialize()
        backup.restore(json)
        assertEquals(before, db.savedExerciseDao().all())
    }

    @Test
    fun `restauro troca os salvos que havia pelos do backup`() = withBackup { db, backup ->
        db.savedExerciseDao().insert(com.kalendrys.app.data.local.entity.SavedExercise(name = "Local", sets = 1, reps = 1, createdAt = 1))
        db.taskDao().insert(Task(title = "local", createdAt = 1))
        backup.restore(backup(version = 6, tasks = emptyList()))
        assertTrue(db.savedExerciseDao().all().isEmpty())
        assertTrue(db.taskDao().all().isEmpty())
    }
}
