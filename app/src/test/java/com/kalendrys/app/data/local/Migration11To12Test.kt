package com.kalendrys.app.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import com.kalendrys.app.testutil.AbsolutePathOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Migração 11 → 12 (v1.10.0, ADR-0002 16.10.3) com banco real de cada caso, validada contra o
 * `12.json`: tipo da rotina pela categoria, exercícios salvos, rotina sem lista, lista de UMA tarefa
 * (cópia para as outras), vínculo órfão limpo, lista sem tarefa apagada com os itens, índice único.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.TaskDatesSpec()),
        AbsolutePathOpenHelperFactory(),
    )

    // --- Dados no formato do banco 11 ------------------------------------------------------------

    private fun SupportSQLiteDatabase.task(id: Long, createdAt: Long, listId: Long?) = execSQL(
        "INSERT INTO tasks (id, title, notes, createdAt, reminderRepeat, shoppingListId) VALUES (?, ?, '', ?, 'NONE', ?)",
        arrayOf<Any?>(id, "Tarefa $id", createdAt, listId),
    )

    private fun SupportSQLiteDatabase.routine(id: Long, categoryId: Long? = null, listId: Long? = null) = execSQL(
        "INSERT INTO routines (id, title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, " +
            "createdAt, categoryId, shoppingListId) VALUES (?, ?, '', 'DEFAULT', 'DEFAULT', 600, 30, 127, 1, 0, ?, ?)",
        arrayOf<Any?>(id, "Rotina $id", categoryId, listId),
    )

    private fun SupportSQLiteDatabase.category(id: Long, kind: String) = execSQL(
        "INSERT INTO routine_category (id, name, color, kind) VALUES (?, ?, 'DEFAULT', ?)",
        arrayOf<Any>(id, "Cat $id", kind),
    )

    private fun SupportSQLiteDatabase.list(id: Long, name: String = "Lista $id", resetKey: String? = null) = execSQL(
        "INSERT INTO shopping_list (id, name, createdAt, lastResetKey) VALUES (?, ?, 7, ?)",
        arrayOf<Any?>(id, name, resetKey),
    )

    private fun SupportSQLiteDatabase.item(id: Long, listId: Long, position: Int = 0, picked: Boolean = false) = execSQL(
        "INSERT INTO shopping_item (id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset) " +
            "VALUES (?, ?, ?, 2, 150, ?, ?, 5, 1)",
        arrayOf<Any>(id, listId, "Item $id", if (picked) 1 else 0, position),
    )

    private fun SupportSQLiteDatabase.exercise(id: Long, routineId: Long) = execSQL(
        "INSERT INTO exercise_item (id, routineId, name, sets, reps, load, restSeconds, position, createdAt) " +
            "VALUES (?, ?, 'Supino', 3, 10, '20kg', 60, 0, 1)",
        arrayOf<Any>(id, routineId),
    )

    private fun migrate(name: String, build: SupportSQLiteDatabase.() -> Unit): SupportSQLiteDatabase {
        helper.createDatabase(name, 11).use { it.build() }
        return helper.runMigrationsAndValidate(name, 12, true, Migration11To12)
    }

    // --- Leitura do banco 12 ---------------------------------------------------------------------

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long?> = query(sql).use { c ->
        buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getLong(0)) }
    }

    private fun SupportSQLiteDatabase.taskLinks(): Map<Long, Long?> = query("SELECT id, shoppingListId FROM tasks").use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) }
    }

    private fun SupportSQLiteDatabase.routineKinds(): Map<Long, String> = query("SELECT id, kind FROM routines").use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
    }

    // --- Casos ------------------------------------------------------------------------------------

    @Test
    fun `rotina com lista - fica a rotina, sem vinculo, e a lista que so ela usava sai com os itens`() {
        migrate("m12-rotina-lista") {
            list(1); item(10, 1); item(11, 1)
            routine(1, listId = 1)
        }.use { db ->
            assertEquals(listOf(1L), db.longs("SELECT id FROM routines"))
            assertEquals(listOf<Long?>(null), db.longs("SELECT shoppingListId FROM routines"))
            assertTrue(db.longs("SELECT id FROM shopping_list").isEmpty())
            assertTrue(db.longs("SELECT id FROM shopping_item").isEmpty())
        }
    }

    @Test
    fun `lista avulsa - apagada com os itens, e item sem lista tambem`() {
        migrate("m12-avulsa") {
            list(1); item(10, 1)
            list(2); item(20, 2)
            task(1, 0, 2)
            item(30, 99)
        }.use { db ->
            assertEquals(listOf(2L), db.longs("SELECT id FROM shopping_list"))
            assertEquals(listOf(20L), db.longs("SELECT id FROM shopping_item"))
            assertEquals(mapOf(1L to 2L), db.taskLinks())
        }
    }

    @Test
    fun `lista de duas tarefas - a mais antiga fica e a outra recebe copia com os itens`() {
        migrate("m12-duas") {
            list(1, name = "Feira", resetKey = "task-log:3"); item(10, 1, position = 1, picked = true); item(11, 1, position = 0)
            task(1, createdAt = 50, listId = 1)
            task(2, createdAt = 20, listId = 1)
        }.use { db ->
            assertEquals(mapOf(1L to 2L, 2L to 1L), db.taskLinks())
            db.query("SELECT id, name, lastResetKey FROM shopping_list ORDER BY id").use { c ->
                c.moveToNext(); assertEquals(1L, c.getLong(0)); assertEquals("task-log:3", c.getString(2))
                c.moveToNext(); assertEquals(2L, c.getLong(0)); assertEquals("Feira", c.getString(1)); assertTrue(c.isNull(2))
            }
            // Cópia: ids novos (12, 13) na ordem da lista; marcação copiada, sem reinício pendente.
            db.query("SELECT id, listId, name, picked, position, pickedBeforeReset FROM shopping_item WHERE listId = 2 ORDER BY id").use { c ->
                c.moveToNext(); assertEquals(12L, c.getLong(0)); assertEquals("Item 11", c.getString(2)); assertEquals(0, c.getInt(3)); assertEquals(0, c.getInt(5))
                c.moveToNext(); assertEquals(13L, c.getLong(0)); assertEquals("Item 10", c.getString(2)); assertEquals(1, c.getInt(3)); assertEquals(0, c.getInt(5))
            }
            assertEquals("os itens da original ficam", listOf(10L, 11L), db.longs("SELECT id FROM shopping_item WHERE listId = 1 ORDER BY id"))
        }
    }

    @Test
    fun `lista de tres tarefas - duas copias`() {
        migrate("m12-tres") {
            list(1); item(10, 1)
            task(1, 10, 1); task(2, 20, 1); task(3, 30, 1)
        }.use { db ->
            assertEquals(mapOf(1L to 1L, 2L to 2L, 3L to 3L), db.taskLinks())
            assertEquals(listOf(1L, 2L, 3L), db.longs("SELECT listId FROM shopping_item ORDER BY id"))
        }
    }

    @Test
    fun `tarefa e rotina na mesma lista - a tarefa fica com ela, a rotina perde o vinculo`() {
        migrate("m12-tarefa-rotina") {
            list(1); item(10, 1)
            task(1, 0, 1)
            routine(1, listId = 1)
        }.use { db ->
            assertEquals(mapOf(1L to 1L), db.taskLinks())
            assertEquals(listOf<Long?>(null), db.longs("SELECT shoppingListId FROM routines"))
            assertEquals(listOf(10L), db.longs("SELECT id FROM shopping_item"))
        }
    }

    @Test
    fun `vinculo orfao de tarefa e limpo`() {
        migrate("m12-orfao") {
            task(1, 0, 404)
        }.use { db ->
            assertEquals(mapOf(1L to null), db.taskLinks())
        }
    }

    @Test
    fun `categoria de cada tipo decide o tipo da rotina`() {
        migrate("m12-tipos") {
            category(1, "SIMPLE"); category(2, "EXERCICIO"); category(3, "COMPRAS")
            routine(1, categoryId = 1); routine(2, categoryId = 2); routine(3, categoryId = 3)
            routine(4); routine(5, categoryId = 404)
        }.use { db ->
            assertEquals(
                mapOf(1L to "NONE", 2L to "EXERCISE", 3L to "NONE", 4L to "NONE", 5L to "NONE"),
                db.routineKinds(),
            )
            assertEquals("a coluna antiga e a tabela de categorias ficam", listOf(1L, 2L, 3L), db.longs("SELECT id FROM routine_category ORDER BY id"))
            assertEquals(listOf(1L, 2L, 3L, null, 404L), db.longs("SELECT categoryId FROM routines ORDER BY id"))
        }
    }

    @Test
    fun `exercicios escondidos ficam guardados`() {
        migrate("m12-exercicios") {
            // Rotina que já foi de categoria Exercício e mudou para uma simples: os exercícios ficam.
            category(1, "SIMPLE")
            routine(1, categoryId = 1)
            exercise(10, 1)
        }.use { db ->
            assertEquals(mapOf(1L to "NONE"), db.routineKinds())
            assertEquals(listOf(10L), db.longs("SELECT id FROM exercise_item"))
        }
    }

    @Test
    fun `tabela de exercicios salvos nasce vazia e aceita gravacao`() {
        migrate("m12-salvos") {}.use { db ->
            assertTrue(db.longs("SELECT id FROM saved_exercise").isEmpty())
            db.execSQL("INSERT INTO saved_exercise (name, sets, reps, load, restSeconds, createdAt) VALUES ('Agachamento', 4, 8, NULL, 90, 1)")
            assertEquals(1, db.longs("SELECT id FROM saved_exercise").size)
        }
    }

    @Test
    fun `indice unico - depois da migracao o banco recusa duas tarefas na mesma lista`() {
        migrate("m12-indice") {
            list(1)
            task(1, 0, 1); task(2, 1, 1); task(3, 2, null); task(4, 3, null)
        }.use { db ->
            // A 2 ganhou a cópia; várias tarefas sem lista continuam valendo (NULL não conta no índice).
            assertEquals(mapOf(1L to 1L, 2L to 2L, 3L to null, 4L to null), db.taskLinks())
            try {
                db.execSQL("UPDATE tasks SET shoppingListId = 1 WHERE id = 2")
                fail("duas tarefas na mesma lista deviam ser recusadas")
            } catch (expected: SQLiteConstraintException) {
                // ok
            }
        }
    }

    @Test
    fun `banco 11 vazio migra sem erro`() {
        migrate("m12-vazio") {}.use { db ->
            assertTrue(db.longs("SELECT id FROM tasks").isEmpty())
            assertNull(db.longs("SELECT MAX(id) FROM shopping_list").single())
        }
    }
}
