package com.kalendrys.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import com.kalendrys.app.testutil.AbsolutePathOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kalendrys.app.data.repository.BackupManager
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Teste cruzado da v1.10.0 (ADR-0002 16.10.3): os MESMOS dados como banco 11 (migrado para o 12) e
 * como backup v6 (restaurado) dão o mesmo resultado nas tabelas que as regras tocam. As duas pontas
 * usam as funções do [com.kalendrys.app.domain.ListOwnership]. Também: o restauro do backup v7
 * exportado do resultado não muda nada (ida e volta).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration11To12VsBackupTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.TaskDatesSpec()),
        AbsolutePathOpenHelperFactory(),
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Os dados, uma vez só, para os dois formatos.
    private data class T(val id: Long, val createdAt: Long, val listId: Long?)
    private data class R(val id: Long, val categoryId: Long?, val listId: Long?)
    private data class L(val id: Long, val name: String, val resetKey: String?)
    private data class I(val id: Long, val listId: Long, val position: Int, val picked: Boolean, val before: Boolean)

    private val categories = listOf(1L to "SIMPLE", 2L to "EXERCICIO", 3L to "COMPRAS")
    private val routines = listOf(R(1, 2, 1), R(2, 1, 3), R(3, null, null), R(4, 3, 5), R(5, 404, null))
    private val tasks = listOf(
        T(1, 50, 1), // lista 1, dividida com a rotina 1 e com a tarefa 2
        T(2, 20, 1), // mais antiga da lista 1
        T(3, 30, 2), T(4, 40, 2), T(5, 10, 2), // lista 2 de três tarefas
        T(6, 0, 404), // vínculo órfão
        T(7, 0, null),
    )
    private val lists = listOf(L(1, "Mercado", "task-log:8"), L(2, "Feira", null), L(3, "Só rotina", null), L(4, "Avulsa", null), L(5, "Rotina compras", null))
    private val items = listOf(
        I(10, 1, 1, true, true), I(11, 1, 0, false, false),
        I(20, 2, 0, true, false),
        I(30, 3, 0, false, false),
        I(40, 4, 0, false, false),
        I(50, 5, 0, true, true),
        I(90, 77, 0, false, false), // sem lista
    )

    private data class State(
        val tasks: List<Pair<Long, Long?>>,
        val routines: List<Triple<Long, String, Long?>>,
        val lists: List<List<String?>>,
        val items: List<List<String?>>,
        val exercises: List<Long>,
    )

    private fun <T> SupportSQLiteDatabase.rows(sql: String, read: (android.database.Cursor) -> T): List<T> =
        query(sql).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    private fun SupportSQLiteDatabase.state() = State(
        tasks = rows("SELECT id, shoppingListId FROM tasks ORDER BY id") { c -> c.getLong(0) to (if (c.isNull(1)) null else c.getLong(1)) },
        routines = rows("SELECT id, kind, shoppingListId FROM routines ORDER BY id") { c ->
            Triple(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))
        },
        lists = rows("SELECT id, name, createdAt, lastResetKey FROM shopping_list ORDER BY id") { c -> (0..3).map { if (c.isNull(it)) null else c.getString(it) } },
        items = rows("SELECT id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset FROM shopping_item ORDER BY id") { c ->
            (0..8).map { if (c.isNull(it)) null else c.getString(it) }
        },
        exercises = rows("SELECT id FROM exercise_item ORDER BY id") { c -> c.getLong(0) },
    )

    private fun migrated(): State {
        val name = "m12-cruzado"
        helper.createDatabase(name, 11).use { db ->
            categories.forEach { (id, kind) -> db.execSQL("INSERT INTO routine_category (id, name, color, kind) VALUES (?, 'c', 'DEFAULT', ?)", arrayOf<Any>(id, kind)) }
            routines.forEach { r ->
                db.execSQL(
                    "INSERT INTO routines (id, title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt, " +
                        "categoryId, shoppingListId) VALUES (?, 'r', '', 'DEFAULT', 'DEFAULT', 600, 30, 127, 1, 0, ?, ?)",
                    arrayOf<Any?>(r.id, r.categoryId, r.listId),
                )
            }
            tasks.forEach { t ->
                db.execSQL(
                    "INSERT INTO tasks (id, title, notes, createdAt, reminderRepeat, shoppingListId) VALUES (?, 't', '', ?, 'NONE', ?)",
                    arrayOf<Any?>(t.id, t.createdAt, t.listId),
                )
            }
            lists.forEach { l -> db.execSQL("INSERT INTO shopping_list (id, name, createdAt, lastResetKey) VALUES (?, ?, 7, ?)", arrayOf<Any?>(l.id, l.name, l.resetKey)) }
            items.forEach { i ->
                db.execSQL(
                    "INSERT INTO shopping_item (id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset) " +
                        "VALUES (?, ?, ?, 2, 150, ?, ?, 5, ?)",
                    arrayOf<Any>(i.id, i.listId, "i${i.id}", if (i.picked) 1 else 0, i.position, if (i.before) 1 else 0),
                )
            }
            db.execSQL("INSERT INTO exercise_item (id, routineId, name, sets, reps, position, createdAt) VALUES (100, 2, 'Supino', 3, 10, 0, 1)")
        }
        return helper.runMigrationsAndValidate(name, 12, true, Migration11To12).use { it.state() }
    }

    private fun backupV6(): String = JSONObject()
        .put("schemaVersion", 6)
        .put("databaseVersion", 11)
        .put("tasks", JSONArray(tasks.map { t -> JSONObject().put("id", t.id).put("title", "t").put("notes", "").put("createdAt", t.createdAt).put("repeat", "NONE").put("shoppingListId", t.listId ?: JSONObject.NULL) }))
        .put("completionLogs", JSONArray())
        .put(
            "routines",
            JSONArray(
                routines.map { r ->
                    JSONObject().put("id", r.id).put("title", "r").put("notes", "").put("icon", "DEFAULT").put("color", "DEFAULT")
                        .put("startMinute", 600).put("durationMinutes", 30).put("daysOfWeek", 127).put("remind", true).put("createdAt", 0)
                        .put("categoryId", r.categoryId ?: JSONObject.NULL).put("shoppingListId", r.listId ?: JSONObject.NULL)
                },
            ),
        )
        .put("routineCompletions", JSONArray())
        .put("routineCategories", JSONArray(categories.map { (id, kind) -> JSONObject().put("id", id).put("name", "c").put("color", "DEFAULT").put("kind", kind) }))
        .put("routineOverrides", JSONArray())
        .put("shoppingLists", JSONArray(lists.map { l -> JSONObject().put("id", l.id).put("name", l.name).put("createdAt", 7).put("lastResetKey", l.resetKey ?: JSONObject.NULL) }))
        .put(
            "shoppingItems",
            JSONArray(
                items.map { i ->
                    JSONObject().put("id", i.id).put("listId", i.listId).put("name", "i${i.id}").put("quantity", 2).put("priceCents", 150)
                        .put("picked", i.picked).put("position", i.position).put("createdAt", 5).put("pickedBeforeReset", i.before)
                },
            ),
        )
        .put("exerciseItems", JSONArray(listOf(JSONObject().put("id", 100).put("routineId", 2).put("name", "Supino").put("sets", 3).put("reps", 10).put("position", 0).put("createdAt", 1))))
        .toString()

    private fun withRestoredDb(block: suspend (AppDatabase, BackupManager) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val settings = SettingsRepository(
                PreferenceDataStoreFactory.create { File(context.cacheDir, "m12-cruzado-${System.nanoTime()}.preferences_pb") },
            )
            block(db, BackupManager(db, context.contentResolver, settings, clock = SystemAppClock))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migracao 11 para 12 e restauro de backup v6 dao o mesmo resultado`() {
        val migrated = migrated()
        withRestoredDb { db, backup ->
            backup.restore(backupV6())
            assertEquals(migrated, db.openHelper.readableDatabase.state())
        }
        // Conferência do caso, para o teste não passar com os dois lados errados do mesmo jeito.
        assertEquals(listOf(1L to 6L, 2L to 1L, 3L to 7L, 4L to 8L, 5L to 2L, 6L to null, 7L to null), migrated.tasks)
        assertEquals(listOf("EXERCISE", "NONE", "NONE", "NONE", "NONE"), migrated.routines.map { it.second })
        assertTrue(migrated.routines.all { it.third == null })
        assertEquals(listOf("1", "2", "6", "7", "8"), migrated.lists.map { it[0] })
        assertEquals(listOf(100L), migrated.exercises)
    }

    @Test
    fun `exercicio salvo de backup adulterado entra pela regra do app`() {
        // OBS-2 do Lince (T1): séries 0, repetições negativas, carga em branco e descanso negativo.
        val json = JSONObject(backupV6())
            .put("schemaVersion", 7)
            .put(
                "savedExercises",
                JSONArray(listOf(JSONObject().put("id", 1).put("name", "  Remada ").put("sets", 0).put("reps", -2).put("load", "  ").put("restSeconds", -5).put("createdAt", 9))),
            )
            .toString()
        withRestoredDb { db, backup ->
            backup.restore(json)
            val saved = db.savedExerciseDao().all().single()
            assertEquals("Remada", saved.name)
            assertEquals(1, saved.sets)
            assertEquals(1, saved.reps)
            assertEquals(null, saved.load)
            assertEquals(null, saved.restSeconds)
        }
    }

    @Test
    fun `ida e volta do backup v7 nao muda nada`() {
        withRestoredDb { db, backup ->
            backup.restore(backupV6())
            val before = db.openHelper.readableDatabase.state()
            val (json, _) = backup.serialize()
            val root = JSONObject(json)
            assertEquals(7, root.getInt("schemaVersion"))
            assertEquals(12, root.getInt("databaseVersion"))
            assertTrue("rotina não leva lista no v7", (0 until root.getJSONArray("routines").length()).none { root.getJSONArray("routines").getJSONObject(it).has("shoppingListId") })

            backup.restore(json)
            assertEquals(before, db.openHelper.readableDatabase.state())
        }
    }
}
