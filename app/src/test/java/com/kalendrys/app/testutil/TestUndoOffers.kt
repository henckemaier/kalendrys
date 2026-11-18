package com.kalendrys.app.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
 * [UndoOffers] de teste sobre [db]: tarefas num [TaskRepository] com agendador falso e rotinas em
 * [routines]. Restauro em [scope] (padrão: imediato, fora do Main que outros testes trocam).
 */
fun testUndoOffers(
    db: AppDatabase,
    routines: RoutineRepository = testRoutineRepository(db),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    clock: () -> Long = { System.currentTimeMillis() },
): UndoOffers {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
    return UndoOffers(tasks, routines, scope, clock)
}
