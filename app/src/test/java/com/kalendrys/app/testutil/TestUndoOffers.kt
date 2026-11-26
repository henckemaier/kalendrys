package com.kalendrys.app.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
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
        InMemoryRoutineCatchUpMark(),
        clock = SystemAppClock,
    )
}

/**
 * [UndoOffers] de teste sobre o banco de [testDb]: tarefas em [tasks] (por padrão, um [TaskRepository]
 * com agendador falso) e rotinas em [routines]. A ação e o restauro rodam no
 * [ViewModelTestDatabase.appScope], que o [ViewModelTestDatabase.close] espera (ou cancela e espera)
 * ANTES de fechar o banco: nada atrasado sobra para bater no banco fechado no teste seguinte.
 */
fun testUndoOffers(
    testDb: ViewModelTestDatabase,
    routines: RoutineRepository = testRoutineRepository(testDb.db),
    clock: () -> Long = { System.currentTimeMillis() },
    tasks: TaskRepository = testTaskRepository(testDb.db),
): UndoOffers = UndoOffers(tasks, routines, testDb.appScope(), clockOf(now = clock))

/** [TaskRepository] de teste sobre [db], com agendador falso. */
fun testTaskRepository(db: AppDatabase): TaskRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
}

/** Alvo de Desfazer de uma tarefa excluída SEM lista (v1.10.0: a oferta guarda tarefa, lista e itens). */
fun deletedTaskTarget(task: com.kalendrys.app.data.local.entity.Task): com.kalendrys.app.UndoTarget.DeletedTask =
    com.kalendrys.app.UndoTarget.DeletedTask(com.kalendrys.app.data.local.dao.TaskWithList(task, null, emptyList()))
