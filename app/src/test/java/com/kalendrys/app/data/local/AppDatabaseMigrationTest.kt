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
 * Observação: o histórico de schemas começa na v2 (2.json); não há 1.json exportado, então a
 * MIGRATION_1_2 não pode ser validada pelo helper. O caminho coberto é v2 → v5 (atual).
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
