package com.kalendrys.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kalendrys.app.testutil.AbsolutePathOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Casos extras da migração 11 → 12 (v1.10.0, ADR-0002 16.10.3) pedidos pelo Esquadro, além do
 * [Migration11To12Test]: muitas rotinas em categoria Exercício (A2: ler e gravar a mesma tabela),
 * três tarefas com o MESMO createdAt (empate pelo menor id, inserido fora de ordem), id da cópia que
 * coincide com um vínculo órfão e com itens órfãos, e a cadeia real 7 → 12 com dados em cada etapa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration11To12ExtrasTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.TaskDatesSpec()),
        AbsolutePathOpenHelperFactory(),
    )

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

    private fun SupportSQLiteDatabase.list(id: Long) = execSQL(
        "INSERT INTO shopping_list (id, name, createdAt, lastResetKey) VALUES (?, ?, 7, NULL)",
        arrayOf<Any?>(id, "Lista $id"),
    )

    private fun SupportSQLiteDatabase.item(id: Long, listId: Long, name: String = "Item $id", position: Int = 0) = execSQL(
        "INSERT INTO shopping_item (id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset) " +
            "VALUES (?, ?, ?, 1, NULL, 0, ?, 5, 0)",
        arrayOf<Any>(id, listId, name, position),
    )

    private fun migrate(name: String, build: SupportSQLiteDatabase.() -> Unit): SupportSQLiteDatabase {
        helper.createDatabase(name, 11).use { it.build() }
        return helper.runMigrationsAndValidate(name, 12, true, Migration11To12)
    }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long?> = query(sql).use { c ->
        buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getLong(0)) }
    }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String?> = query(sql).use { c ->
        buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getString(0)) }
    }

    private fun SupportSQLiteDatabase.taskLinks(): Map<Long, Long?> = query("SELECT id, shoppingListId FROM tasks").use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) }
    }

    @Test
    fun `A2 - muitas rotinas em categoria Exercicio ganham o tipo todas, as outras ficam Nenhum`() {
        val count = 600
        migrate("m12x-muitas") {
            category(1, "EXERCICIO"); category(2, "SIMPLE")
            for (id in 1L..count) routine(id, categoryId = if (id % 3 == 0L) 2 else 1, listId = null)
        }.use { db ->
            val kinds = db.query("SELECT id, kind FROM routines ORDER BY id").use { c ->
                buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
            }
            assertEquals(count, kinds.size)
            for ((id, kind) in kinds) {
                assertEquals("rotina $id", if (id % 3 == 0L) "NONE" else "EXERCISE", kind)
            }
        }
    }

    @Test
    fun `tres tarefas com createdAt igual - fica a de menor id, mesmo inserida por ultimo`() {
        migrate("m12x-empate") {
            list(1); item(10, 1, "Arroz", 0); item(11, 1, "Feijão", 1)
            task(9, 100, 1); task(5, 100, 1); task(2, 100, 1)
        }.use { db ->
            // A 2 fica com a lista 1; as cópias vão em ordem de criação e id: 5 → 2, 9 → 3.
            assertEquals(mapOf(2L to 1L, 5L to 2L, 9L to 3L), db.taskLinks())
            assertEquals(listOf(1L, 2L, 3L), db.longs("SELECT id FROM shopping_list ORDER BY id"))
            for (listId in 1L..3L) {
                assertEquals("lista $listId", listOf("Arroz", "Feijão"), db.strings("SELECT name FROM shopping_item WHERE listId = $listId ORDER BY position"))
            }
        }
    }

    @Test
    fun `id da copia coincide com vinculo orfao e com itens orfaos - a copia nasce limpa e o orfao nao a herda`() {
        migrate("m12x-colisao") {
            list(1); item(10, 1, "Original")
            list(2); item(20, 2, "Outra")
            task(1, 10, 2); task(2, 20, 2) // a 2 recebe a cópia da lista 2, com id 3 (maior + 1)
            task(3, 30, 3) // vínculo órfão para a lista 3, que não existe (o mesmo id que a cópia vai ter)
            item(30, 3, "Órfão A"); item(31, 3, "Órfão B") // itens sem lista, com listId 3
            task(4, 40, 1)
        }.use { db ->
            assertEquals(mapOf(1L to 2L, 2L to 3L, 3L to null, 4L to 1L), db.taskLinks())
            assertEquals(listOf(1L, 2L, 3L), db.longs("SELECT id FROM shopping_list ORDER BY id"))
            assertEquals(
                "a cópia (lista 3) tem só os itens copiados da lista 2, nada dos órfãos",
                listOf("Outra"),
                db.strings("SELECT name FROM shopping_item WHERE listId = 3"),
            )
            assertTrue(db.longs("SELECT id FROM shopping_item WHERE id IN (30, 31)").isEmpty())
            // O item copiado tem id acima de TODOS os existentes, inclusive dos órfãos apagados.
            assertEquals(listOf(32L), db.longs("SELECT id FROM shopping_item WHERE listId = 3"))
        }
    }

    @Test
    fun `cadeia 7 ate 12 com dados reais em cada etapa`() {
        val name = "m12x-cadeia-7-12"
        helper.createDatabase(name, 7).use { v7 ->
            v7.execSQL("INSERT INTO routine_category (id, name, color, kind) VALUES (1, 'Academia', 'DEFAULT', 'EXERCICIO')")
            v7.execSQL("INSERT INTO routine_category (id, name, color, kind) VALUES (2, 'Casa', 'DEFAULT', 'COMPRAS')")
            v7.execSQL(
                "INSERT INTO routines (id, title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt, categoryId) " +
                    "VALUES (1, 'Treino', '', 'STAR', 'BLUE', 1040, 45, 127, 1, 0, 1)",
            )
            v7.execSQL(
                "INSERT INTO routines (id, title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt, categoryId) " +
                    "VALUES (2, 'Mercado', '', 'STAR', 'GREEN', 600, 30, 127, 1, 0, 2)",
            )
        }
        // v8: itens de compras presos à rotina 2.
        helper.runMigrationsAndValidate(name, 8, true).use { v8 ->
            v8.execSQL(
                "INSERT INTO shopping_item (id, routineId, name, quantity, priceCents, picked, position, createdAt) " +
                    "VALUES (1, 2, 'Leite', 1, NULL, 0, 0, 1)",
            )
        }
        // v9: exercício da rotina 1.
        helper.runMigrationsAndValidate(name, 9, true).use { v9 ->
            v9.execSQL(
                "INSERT INTO exercise_item (id, routineId, name, sets, reps, position, createdAt) VALUES (1, 1, 'Supino', 3, 10, 0, 1)",
            )
        }
        // v10: a lista da rotina 2 nasce da conversão; duas tarefas passam a usá-la também (v11).
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATION_9_10).close()
        helper.runMigrationsAndValidate(name, 11, true).use { v11 ->
            val listId = v11.longs("SELECT shoppingListId FROM routines WHERE id = 2").single()!!
            v11.execSQL("INSERT INTO tasks (id, title, notes, createdAt, reminderRepeat, shoppingListId) VALUES (1, 'Feira', '', 50, 'NONE', $listId)")
            v11.execSQL("INSERT INTO tasks (id, title, notes, createdAt, reminderRepeat, shoppingListId) VALUES (2, 'Padaria', '', 20, 'NONE', $listId)")
        }
        helper.runMigrationsAndValidate(name, 12, true, Migration11To12).use { v12 ->
            assertEquals(listOf("EXERCISE", "NONE"), v12.strings("SELECT kind FROM routines ORDER BY id"))
            assertEquals(listOf<Long?>(null, null), v12.longs("SELECT shoppingListId FROM routines ORDER BY id"))
            assertEquals("o exercício fica", listOf("Supino"), v12.strings("SELECT name FROM exercise_item"))
            val links = v12.taskLinks()
            val original = v12.longs("SELECT id FROM shopping_list ORDER BY id").first()!!
            assertEquals("a tarefa mais antiga (2) fica com a lista convertida", original, links[2L])
            assertTrue("a outra recebe a cópia", links[1L] != null && links[1L] != original)
            assertEquals(listOf("Leite", "Leite"), v12.strings("SELECT name FROM shopping_item ORDER BY id"))
            assertEquals(2, v12.longs("SELECT DISTINCT listId FROM shopping_item").size)
            assertTrue(v12.longs("SELECT id FROM saved_exercise").isEmpty())
        }
    }
}
