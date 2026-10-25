package com.kalendrys.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

@Database(
    entities = [Task::class, CompletionLog::class, Routine::class, RoutineCompletion::class],
    version = 4,
    exportSchema = true,
    // v4: rotinas. Só cria tabelas novas; o Room confere o SQL contra schemas/3.json e 4.json.
    autoMigrations = [AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun completionLogDao(): CompletionLogDao

    abstract fun routineDao(): RoutineDao

    abstract fun routineCompletionDao(): RoutineCompletionDao

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
