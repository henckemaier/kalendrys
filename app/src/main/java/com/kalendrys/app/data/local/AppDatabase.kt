package com.kalendrys.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kalendrys.app.data.local.dao.CompletionLogDao
import com.kalendrys.app.data.local.dao.ExerciseItemDao
import com.kalendrys.app.data.local.dao.RoutineCategoryDao
import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.dao.RoutineOverrideDao
import com.kalendrys.app.data.local.dao.ShoppingItemDao
import com.kalendrys.app.data.local.dao.ShoppingListDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.LegacyShoppingLists
import java.time.Instant
import java.time.ZoneId

@Database(
    entities = [
        Task::class,
        CompletionLog::class,
        Routine::class,
        RoutineCompletion::class,
        RoutineCategory::class,
        RoutineOverride::class,
        ShoppingItem::class,
        ExerciseItem::class,
        ShoppingList::class,
    ],
    version = 10,
    exportSchema = true,
    // v4: rotinas (só tabelas novas). v5: data e local das tarefas. v6: recorrência custom
    // (intervalEvery/intervalUnit/byWeekdays em tasks — aditivo com default). v7: fundação de
    // rotinas — colunas novas em routines (categoryId/pausedAt/weekInterval/startEpochDay/
    // endEpochDay) + tabelas routine_category e routine_override. v8: fundação de compras — só a
    // tabela nova shopping_item (lista de compras da rotina). v9: fundação de exercícios — só a
    // tabela nova exercise_item (lista de exercícios da rotina). Até aqui tudo aditivo (sem SQL
    // manual). v10: listas de compras independentes (ADR-0001) — MIGRATION_9_10 manual, com backfill.
    // O Room confere o SQL gerado contra os arquivos de schemas/.
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = AppDatabase.TaskDatesSpec::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun completionLogDao(): CompletionLogDao

    abstract fun routineDao(): RoutineDao

    abstract fun routineCompletionDao(): RoutineCompletionDao

    abstract fun routineCategoryDao(): RoutineCategoryDao

    abstract fun routineOverrideDao(): RoutineOverrideDao

    abstract fun shoppingItemDao(): ShoppingItemDao

    abstract fun shoppingListDao(): ShoppingListDao

    abstract fun exerciseItemDao(): ExerciseItemDao

    /**
     * v5: antes a tarefa só tinha o lembrete. A que tinha lembrete ganha como data o dia dele;
     * as outras ficam sem data — e, sem repetição, as já concluídas passam a contar como feitas.
     */
    class TaskDatesSpec : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val zone = ZoneId.systemDefault()
            db.query("SELECT id, reminderAt FROM tasks WHERE reminderAt IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) {
                    val day = Instant.ofEpochMilli(cursor.getLong(1)).atZone(zone).toLocalDate().toEpochDay()
                    db.execSQL("UPDATE tasks SET dueDate = ? WHERE id = ?", arrayOf<Any>(day, cursor.getLong(0)))
                }
            }
        }
    }

    companion object {
        /** v2: lembretes recorrentes. Tarefas existentes ficam com lembrete único. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderRepeat TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /** v3: índice para as consultas de conclusões por período. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_completion_log_completedAt` ON `completion_log` (`completedAt`)")
            }
        }

        /**
         * v10: a lista de compras vira entidade própria (ADR-0001, 4.2). Os itens deixam de apontar
         * para a rotina (`routineId`) e passam a apontar para a lista (`listId`); rotinas e tarefas
         * ganham `shoppingListId`. O backfill usa a MESMA regra do restauro de backup antigo
         * ([LegacyShoppingLists]): uma lista por rotina com itens, em ordem crescente de routineId.
         * Ids, marcações, posições e datas dos itens são preservados. Roda na transação do Room.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val titles = mutableMapOf<Long, String>()
                db.query("SELECT id, title FROM routines").use { c ->
                    while (c.moveToNext()) titles[c.getLong(0)] = c.getString(1)
                }
                val legacy = mutableListOf<LegacyShoppingLists.LegacyItem>()
                db.query(
                    "SELECT id, routineId, name, quantity, priceCents, picked, position, createdAt FROM shopping_item",
                ).use { c ->
                    while (c.moveToNext()) {
                        legacy += LegacyShoppingLists.LegacyItem(
                            id = c.getLong(0),
                            routineId = c.getLong(1),
                            name = c.getString(2),
                            quantity = c.getInt(3),
                            priceCents = if (c.isNull(4)) null else c.getLong(4),
                            picked = c.getInt(5) != 0,
                            position = c.getInt(6),
                            createdAt = c.getLong(7),
                        )
                    }
                }
                val converted = LegacyShoppingLists.convert(legacy, titles)

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_list` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastResetKey` TEXT)",
                )
                db.execSQL("ALTER TABLE `routines` ADD COLUMN `shoppingListId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `shoppingListId` INTEGER DEFAULT NULL")
                db.execSQL("DROP TABLE `shopping_item`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shopping_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`listId` INTEGER NOT NULL, `name` TEXT NOT NULL, `quantity` INTEGER NOT NULL, " +
                        "`priceCents` INTEGER, `picked` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `pickedBeforeReset` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_item_listId` ON `shopping_item` (`listId`)")

                for (list in converted.lists) {
                    db.execSQL(
                        "INSERT INTO shopping_list (id, name, createdAt, lastResetKey) VALUES (?, ?, ?, NULL)",
                        arrayOf<Any>(list.id, list.name, list.createdAt),
                    )
                }
                for ((routineId, listId) in converted.routineLinks) {
                    db.execSQL("UPDATE routines SET shoppingListId = ? WHERE id = ?", arrayOf<Any>(listId, routineId))
                }
                for (item in converted.items) {
                    db.execSQL(
                        "INSERT INTO shopping_item (id, listId, name, quantity, priceCents, picked, position, createdAt, " +
                            "pickedBeforeReset) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                        arrayOf<Any?>(
                            item.id, item.listId, item.name, item.quantity, item.priceCents,
                            if (item.picked) 1 else 0, item.position, item.createdAt,
                        ),
                    )
                }
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kalendrys.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_9_10)
                .build()
    }
}
