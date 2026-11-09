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
import com.kalendrys.app.data.local.dao.RoutineCompletionDao
import com.kalendrys.app.data.local.dao.RoutineDao
import com.kalendrys.app.data.local.dao.TaskDao
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import java.time.Instant
import java.time.ZoneId

@Database(
    entities = [Task::class, CompletionLog::class, Routine::class, RoutineCompletion::class],
    version = 6,
    exportSchema = true,
    // v4: rotinas (só tabelas novas). v5: data e local das tarefas. v6: recorrência custom
    // (intervalEvery/intervalUnit/byWeekdays em tasks — aditivo com default). O Room confere o
    // SQL gerado contra os arquivos de schemas/.
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = AppDatabase.TaskDatesSpec::class),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun completionLogDao(): CompletionLogDao

    abstract fun routineDao(): RoutineDao

    abstract fun routineCompletionDao(): RoutineCompletionDao

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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kalendrys.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
