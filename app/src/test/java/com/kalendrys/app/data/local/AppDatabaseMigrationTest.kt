package com.kalendrys.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * cadeia completa v1 → v8 fica coberta: a MIGRATION_1_2 é exercitada e validada pelo helper e as
 * demais migrações (inclusive a auto 5→6 da recorrência custom, a auto 6→7 da fundação de rotinas
 * e a auto 7→8 da fundação de compras) seguem até o schema atual.
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
