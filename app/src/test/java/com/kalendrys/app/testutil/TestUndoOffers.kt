package com.kalendrys.app.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository

/** [RoutineRepository] de teste sobre [db], com alarmes, avisos e marca de catch-up falsos. */
fun testRoutineRepository(db: AppDatabase, alarms: FakeRoutineAlarms? = null): RoutineRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return RoutineRepository(
        db.routineDao(),
        db.routineCompletionDao(),
        db.routineOverrideDao(),
        alarms ?: FakeRoutineAlarms(context),
        FakeRoutineNotifications(context),
        db.shoppingItemDao(),
        InMemoryRoutineCatchUpMark(),
    )
}

/**
 * [UndoOffers] de teste sobre o banco de [testDb]: tarefas num [TaskRepository] com agendador falso e
 * rotinas em [routines]. O restauro roda no [ViewModelTestDatabase.appScope], que o
 * [ViewModelTestDatabase.close] espera (ou cancela e espera) ANTES de fechar o banco: nenhum restauro
 * atrasado sobra para bater no banco fechado no teste seguinte.
 */
fun testUndoOffers(
    testDb: ViewModelTestDatabase,
    routines: RoutineRepository = testRoutineRepository(testDb.db),
    clock: () -> Long = { System.currentTimeMillis() },
): UndoOffers {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = testDb.db
    val tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
    return UndoOffers(tasks, routines, testDb.appScope(), clock)
}
