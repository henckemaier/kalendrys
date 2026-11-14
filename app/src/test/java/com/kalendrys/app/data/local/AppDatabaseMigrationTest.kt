package com.kalendrys.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kalendrys.app.data.repository.BackupManager
import com.kalendrys.app.data.repository.legacyBackup
import com.kalendrys.app.data.repository.legacyItem
import com.kalendrys.app.data.repository.legacyRoutine
import com.kalendrys.app.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Migrações do Room validadas contra os schemas versionados em app/schemas/. Roda no Robolectric
 * como teste unitário (`:app:testDebugUnitTest`), sem precisar de aparelho/emulador.
 *
 * Com o 1.json reconstruído (schema anterior à MIGRATION_1_2, sem a coluna reminderRepeat), a
 * cadeia completa v1 → v9 fica coberta: a MIGRATION_1_2 é exercitada e validada pelo helper e as
 * demais migrações (inclusive a auto 5→6 da recorrência custom, a auto 6→7 da fundação de rotinas,
 * a auto 7→8 da fundação de compras e a auto 8→9 da fundação de exercícios) seguem até o schema atual.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.TaskDatesSpec()),
    )

    @Test
    fun `migra v1 para v2 adiciona reminderRepeat com default NONE e preserva os dados`() {
        val name = "kalendrys-migration-v1-v2"
        helper.createDatabase(name, 1).use { v1 ->
            // Formato v1: a tabela tasks ainda NÃO tem a coluna reminderRepeat.
            v1.execSQL(
                "INSERT INTO tasks (title, notes, createdAt, reminderAt) " +
                    "VALUES ('Tarefa v1', 'nota', 0, 1000)",
            )
        }

        // MIGRATION_1_2: ALTER TABLE tasks ADD COLUMN reminderRepeat TEXT NOT NULL DEFAULT 'NONE'.
        // runMigrationsAndValidate confere o schema resultante contra 2.json e lança se divergir.
        helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2).use { v2 ->
            v2.query("SELECT title, notes, reminderAt, reminderRepeat FROM tasks").use { cursor ->
                assertTrue("a tarefa v1 deveria sobreviver à migração", cursor.moveToFirst())
                assertEquals("Tarefa v1", cursor.getString(0))
                assertEquals("nota", cursor.getString(1))
                assertEquals(1000L, cursor.getLong(2))
                // A coluna nova assume o default 'NONE' nas linhas que já existiam.
                assertEquals("NONE", cursor.getString(3))
            }
        }
    }

    @Test
    fun `migra v2 ate v5 preservando dados e conferindo o schema final`() {
        val name = "kalendrys-migration-v2-v5"
        helper.createDatabase(name, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO tasks (title, notes, createdAt, reminderAt, reminderRepeat) " +
                    "VALUES ('Tarefa', '', 0, 1000, 'NONE')",
            )
        }

        // As auto-migrações (3→4 rotinas, 4→5 datas com a TaskDatesSpec) são descobertas pelo
        // helper a partir da AppDatabase; a 2→3 é manual. runMigrationsAndValidate confere o
        // schema resultante contra 5.json e lança se houver divergência.
        helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_2_3).use { v5 ->
            v5.query("SELECT dueDate, location FROM tasks").use { cursor ->
                assertTrue("a tarefa migrada deveria existir", cursor.moveToFirst())
                // A TaskDatesSpec (4→5) copia a data do lembrete para dueDate quando há lembrete.
                assertFalse("dueDate deveria ter sido preenchido pela TaskDatesSpec", cursor.isNull(0))
                // location é a coluna nova da v5, com default "".
                assertEquals("", cursor.getString(1))
            }
        }
    }

    @Test
    fun `migra v5 para v6 adiciona os campos de recorrencia custom com defaults`() {
        val name = "kalendrys-migration-v5-v6"
        helper.createDatabase(name, 5).use { v5 ->
            // Formato v5: tasks tem dueDate/reminderRepeat/location, mas ainda sem os campos custom.
            v5.execSQL(
                "INSERT INTO tasks (title, notes, createdAt, dueDate, reminderAt, reminderRepeat, location) " +
                    "VALUES ('Tarefa v5', '', 0, 20000, 1000, 'DAILY', 'Casa')",
            )
        }

        // 5→6 é AutoMigration aditiva (intervalEvery/intervalUnit/byWeekdays com default); valida vs 6.json.
        helper.runMigrationsAndValidate(name, 6, true).use { v6 ->
            v6.query(
                "SELECT title, reminderRepeat, location, intervalEvery, intervalUnit, byWeekdays FROM tasks",
            ).use { c ->
                assertTrue("a tarefa v5 deveria sobreviver à migração", c.moveToFirst())
                assertEquals("Tarefa v5", c.getString(0))
                assertEquals("DAILY", c.getString(1))
                assertEquals("Casa", c.getString(2))
                // Defaults dos campos novos nas linhas que já existiam.
                assertEquals(1, c.getInt(3))
                assertTrue("intervalUnit deveria ser NULL por default", c.isNull(4))
                assertEquals(0, c.getInt(5))
            }
        }
    }

    @Test
    fun `migra v6 para v7 adiciona colunas de rotina e as tabelas de categoria e excecao`() {
        val name = "kalendrys-migration-v6-v7"
        helper.createDatabase(name, 6).use { v6 ->
            // Formato v6: routines ainda sem categoryId/pausedAt/weekInterval/startEpochDay/endEpochDay.
            v6.execSQL(
                "INSERT INTO routines " +
                    "(title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt) " +
                    "VALUES ('Treino', '', 'STAR', 'BLUE', 1040, 15, 127, 1, 0)",
            )
        }

        // 6→7 é AutoMigration aditiva (colunas novas com default/nullable + duas tabelas novas);
        // valida vs 7.json.
        helper.runMigrationsAndValidate(name, 7, true).use { v7 ->
            v7.query(
                "SELECT title, weekInterval, categoryId, pausedAt, startEpochDay, endEpochDay FROM routines",
            ).use { c ->
                assertTrue("a rotina v6 deveria sobreviver à migração", c.moveToFirst())
                assertEquals("Treino", c.getString(0))
                // Defaults dos campos novos nas linhas que já existiam.
                assertEquals(1, c.getInt(1))
                assertTrue("categoryId deveria ser NULL por default", c.isNull(2))
                assertTrue("pausedAt deveria ser NULL por default", c.isNull(3))
                assertTrue("startEpochDay deveria ser NULL por default", c.isNull(4))
                assertTrue("endEpochDay deveria ser NULL por default", c.isNull(5))
            }
            assertTrue("tabela routine_category ausente após 6→7", hasTable(v7, "routine_category"))
            assertTrue("tabela routine_override ausente após 6→7", hasTable(v7, "routine_override"))
        }
    }

    @Test
    fun `migra v7 para v8 cria a tabela shopping_item preservando os dados`() {
        val name = "kalendrys-migration-v7-v8"
        helper.createDatabase(name, 7).use { v7 ->
            // Formato v7: ainda sem a tabela shopping_item. Uma rotina já existente deve sobreviver.
            v7.execSQL(
                "INSERT INTO routines " +
                    "(title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt) " +
                    "VALUES ('Mercado', '', 'STAR', 'GREEN', 600, 30, 127, 1, 0)",
            )
        }

        // 7→8 é AutoMigration aditiva (só a tabela nova shopping_item); valida vs 8.json.
        helper.runMigrationsAndValidate(name, 8, true).use { v8 ->
            v8.query("SELECT title FROM routines").use { c ->
                assertTrue("a rotina v7 deveria sobreviver à migração", c.moveToFirst())
                assertEquals("Mercado", c.getString(0))
            }
            assertTrue("tabela shopping_item ausente após 7→8", hasTable(v8, "shopping_item"))
        }
    }

    @Test
    fun `migra v8 para v9 cria a tabela exercise_item preservando os dados`() {
        val name = "kalendrys-migration-v8-v9"
        helper.createDatabase(name, 8).use { v8 ->
            // Formato v8: ainda sem a tabela exercise_item. Uma rotina já existente deve sobreviver.
            v8.execSQL(
                "INSERT INTO routines " +
                    "(title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt) " +
                    "VALUES ('Treino', '', 'STAR', 'BLUE', 1040, 45, 127, 1, 0)",
            )
        }

        // 8→9 é AutoMigration aditiva (só a tabela nova exercise_item); valida vs 9.json.
        helper.runMigrationsAndValidate(name, 9, true).use { v9 ->
            v9.query("SELECT title FROM routines").use { c ->
                assertTrue("a rotina v8 deveria sobreviver à migração", c.moveToFirst())
                assertEquals("Treino", c.getString(0))
            }
            assertTrue("tabela exercise_item ausente após 8→9", hasTable(v9, "exercise_item"))
        }
    }

    @Test
    fun `migra v9 para v10 transforma itens por rotina em listas independentes`() {
        val name = "kalendrys-migration-v9-v10"
        helper.createDatabase(name, 9).use { v9 ->
            // Rotina 2 fora de COMPRAS com itens (hoje oculta), rotina 1 arquivada com itens, rotina 3
            // sem itens, e itens da rotina 99, que não existe.
            v9.execSQL(insertRoutine(1, "Mercado", archivedAt = "50"))
            v9.execSQL(insertRoutine(2, "Feira", archivedAt = "NULL"))
            v9.execSQL(insertRoutine(3, "Sem lista", archivedAt = "NULL"))
            v9.execSQL(insertItem(id = 10, routineId = 2, name = "Banana", picked = 1, position = 1, createdAt = 900))
            v9.execSQL(insertItem(id = 11, routineId = 1, name = "Arroz", picked = 0, position = 0, createdAt = 500))
            v9.execSQL(insertItem(id = 12, routineId = 99, name = "Órfão", picked = 1, position = 0, createdAt = 800))
            v9.execSQL(insertItem(id = 13, routineId = 2, name = "Maçã", picked = 0, position = 0, createdAt = 700, priceCents = "NULL"))
            v9.execSQL(insertItem(id = 14, routineId = 1, name = "Feijão", picked = 1, position = 1, createdAt = 600))
        }

        // Manual (a primeira desde a v3); valida o schema resultante contra 10.json.
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATION_9_10).use { v10 ->
            // Uma lista por routineId, em ordem crescente: 1 → lista 1, 2 → lista 2, 99 → lista 3.
            val lists = v10.rows("SELECT id, name, createdAt, lastResetKey FROM shopping_list ORDER BY id") {
                listOf(it.getLong(0), it.getString(1), it.getLong(2), if (it.isNull(3)) null else it.getString(3))
            }
            assertEquals(
                listOf(
                    listOf(1L, "Mercado", 500L, null),
                    listOf(2L, "Feira", 700L, null),
                    listOf(3L, "Lista de compras", 800L, null),
                ),
                lists,
            )
            // Vínculo preenchido inclusive na arquivada; rotina sem itens não ganha lista vazia.
            val links = v10.rows("SELECT id, shoppingListId FROM routines ORDER BY id") {
                it.getLong(0) to (if (it.isNull(1)) null else it.getLong(1))
            }
            assertEquals(listOf(1L to 1L, 2L to 2L, 3L to null), links)
            val tasksColumn = v10.rows("SELECT COUNT(shoppingListId) FROM tasks") { it.getInt(0) }
            assertEquals(listOf(0), tasksColumn) // a coluna existe e começa vazia

            // Nenhum item se perde; ids, marcações, posições e datas preservados.
            val items = v10.rows(
                "SELECT id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset FROM shopping_item ORDER BY id",
            ) { c -> (0..8).map { i -> if (c.isNull(i)) null else c.getString(i) } }
            assertEquals(
                listOf(
                    listOf("10", "2", "Banana", "2", "150", "1", "1", "900", "0"),
                    listOf("11", "1", "Arroz", "2", "150", "0", "0", "500", "0"),
                    listOf("12", "3", "Órfão", "2", "150", "1", "0", "800", "0"),
                    listOf("13", "2", "Maçã", "2", null, "0", "0", "700", "0"), // preço nulo continua nulo
                    listOf("14", "1", "Feijão", "2", "150", "1", "1", "600", "0"),
                ),
                items,
            )
        }
    }

    @Test
    fun `migra v9 sem itens de compras para v10 sem criar listas`() {
        val name = "kalendrys-migration-v9-v10-empty"
        helper.createDatabase(name, 9).use { it.execSQL(insertRoutine(1, "Treino", archivedAt = "NULL")) }
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATION_9_10).use { v10 ->
            assertEquals(listOf(0), v10.rows("SELECT COUNT(*) FROM shopping_list") { it.getInt(0) })
            assertEquals(listOf<Long?>(null), v10.rows("SELECT shoppingListId FROM routines") { if (it.isNull(0)) null else it.getLong(0) })
        }
    }

    @Test
    fun `cadeia completa v3 ate v10 com dados de compras`() {
        val name = "kalendrys-migration-v3-v10"
        helper.createDatabase(name, 3).use { v3 ->
            v3.execSQL("INSERT INTO tasks (title, notes, createdAt, reminderAt, reminderRepeat) VALUES ('Tarefa', '', 0, NULL, 'NONE')")
        }
        // Sobe até a v9 (auto-migrações) e insere itens no formato antigo antes do 9→10.
        helper.runMigrationsAndValidate(name, 9, true).use { v9 ->
            v9.execSQL(insertRoutine(4, "Mercado", archivedAt = "NULL"))
            v9.execSQL(insertItem(id = 1, routineId = 4, name = "Leite", picked = 1, position = 0, createdAt = 10))
        }
        helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATION_9_10).use { v10 ->
            assertEquals(listOf("Tarefa"), v10.rows("SELECT title FROM tasks") { it.getString(0) })
            assertEquals(listOf(1L), v10.rows("SELECT shoppingListId FROM routines WHERE id = 4") { it.getLong(0) })
            assertEquals(listOf(1L to true), v10.rows("SELECT listId, picked FROM shopping_item") { it.getLong(0) to (it.getInt(1) == 1) })
        }
    }

    private fun insertRoutine(id: Long, title: String, archivedAt: String) =
        "INSERT INTO routines (id, title, notes, icon, color, startMinute, durationMinutes, daysOfWeek, remind, createdAt, archivedAt, weekInterval) " +
            "VALUES ($id, '$title', '', 'STAR', 'GREEN', 600, 30, 127, 1, 0, $archivedAt, 1)"

    /** [priceCents] é SQL literal: "150" ou "NULL" (item sem preço). */
    private fun insertItem(id: Long, routineId: Long, name: String, picked: Int, position: Int, createdAt: Long, priceCents: String = "150") =
        "INSERT INTO shopping_item (id, routineId, name, quantity, priceCents, picked, position, createdAt) " +
            "VALUES ($id, $routineId, '$name', 2, $priceCents, $picked, $position, $createdAt)"

    @Test
    fun `migracao 9 para 10 e restauro de backup v5 dao a mesma saida sobre os mesmos dados`() = runBlocking {
        // Mesmos dados nos dois formatos antigos: banco v9 e backup v5 (itens presos a routineId).
        data class Seed(val id: Long, val routineId: Long, val name: String, val picked: Boolean, val position: Int, val createdAt: Long, val price: Long?)
        val routines = listOf(Triple(1L, "Mercado", true), Triple(2L, "Feira", false), Triple(3L, "Sem lista", false))
        val seeds = listOf(
            Seed(10, 2, "Banana", true, 1, 900, 150),
            Seed(11, 1, "Arroz", false, 0, 500, 150),
            Seed(12, 99, "Órfão", true, 0, 800, null),
            Seed(13, 2, "Maçã", false, 0, 700, null),
            Seed(14, 1, "Feijão", true, 1, 600, 150),
        )

        val name = "kalendrys-migration-vs-backup"
        helper.createDatabase(name, 9).use { v9 ->
            routines.forEach { (id, title, archived) -> v9.execSQL(insertRoutine(id, title, if (archived) "50" else "NULL")) }
            seeds.forEach { s ->
                v9.execSQL(insertItem(s.id, s.routineId, s.name, if (s.picked) 1 else 0, s.position, s.createdAt, s.price?.toString() ?: "NULL"))
            }
        }
        val migrated = helper.runMigrationsAndValidate(name, 10, true, AppDatabase.MIGRATION_9_10).use { v10 ->
            Triple(
                v10.rows("SELECT id, name, createdAt, lastResetKey FROM shopping_list ORDER BY id") { c -> (0..3).map { if (c.isNull(it)) null else c.getString(it) } },
                v10.rows("SELECT id, listId, name, quantity, priceCents, picked, position, createdAt, pickedBeforeReset FROM shopping_item ORDER BY id") { c ->
                    (0..8).map { if (c.isNull(it)) null else c.getString(it) }
                },
                v10.rows("SELECT id, shoppingListId FROM routines ORDER BY id") { c -> c.getLong(0) to (if (c.isNull(1)) null else c.getLong(1)) },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val backup = legacyBackup(
                5,
                routines = routines.map { (id, title, archived) -> legacyRoutine(id, title, archived) },
                shoppingItems = seeds.map { legacyItem(it.id, it.routineId, it.name, it.picked, it.createdAt, it.price, it.position) },
                exerciseItems = emptyList(),
            )
            val settings = SettingsRepository(
                PreferenceDataStoreFactory.create { File(context.cacheDir, "migration-vs-backup-${System.nanoTime()}.preferences_pb") },
            )
            BackupManager(db, context.contentResolver, settings).restore(backup.toString())

            val restoredLists = db.shoppingListDao().all().map { listOf(it.id.toString(), it.name, it.createdAt.toString(), it.lastResetKey) }
            val restoredItems = db.shoppingItemDao().all().sortedBy { it.id }.map {
                listOf(
                    it.id.toString(), it.listId.toString(), it.name, it.quantity.toString(), it.priceCents?.toString(),
                    if (it.picked) "1" else "0", it.position.toString(), it.createdAt.toString(), if (it.pickedBeforeReset) "1" else "0",
                )
            }
            val restoredLinks = db.routineDao().all().sortedBy { it.id }.map { it.id to it.shoppingListId }

            assertEquals(migrated.first, restoredLists)
            assertEquals(migrated.second, restoredItems)
            assertEquals(migrated.third, restoredLinks)
        } finally {
            db.close()
        }
    }

    private fun <T> SupportSQLiteDatabase.rows(sql: String, read: (android.database.Cursor) -> T): List<T> =
        query(sql).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    @Test
    fun `a auto-migracao v3 para v4 abre e cria as tabelas de rotina`() {
        val name = "kalendrys-migration-v3-v4"
        helper.createDatabase(name, 3).close()

        helper.runMigrationsAndValidate(name, 4, true).use { v4 ->
            assertTrue("tabela routines ausente após 3→4", hasTable(v4, "routines"))
            assertTrue("tabela routine_completion ausente após 3→4", hasTable(v4, "routine_completion"))
        }
    }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any>(table))
            .use { it.moveToFirst() }
}
