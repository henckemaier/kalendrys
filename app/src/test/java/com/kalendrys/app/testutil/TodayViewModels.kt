package com.kalendrys.app.testutil

import android.content.Context
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.ui.today.TodayViewModel
import java.time.DayOfWeek

/** Repositórios da tela Hoje sobre o banco de teste, com alarmes e notificações falsos. */
class TodayRepositories(context: Context, db: AppDatabase) {
    val routines = RoutineRepository(
        db.routineDao(),
        db.routineCompletionDao(),
        db.routineOverrideDao(),
        FakeRoutineAlarms(context),
        FakeRoutineNotifications(context),
        db.shoppingItemDao(),
        InMemoryRoutineCatchUpMark(),
    )
    val tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
    val shopping = ShoppingRepository(db)

    /** O mesmo retrato do widget Resumo (fonte única das pendências da Hoje). */
    val today = TodayRepository(tasks, routines, shopping)

    fun viewModel(firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY, draftStore: NewTaskDraftStore = NewTaskDraftStore()) =
        TodayViewModel(routines, tasks, firstDayOfWeek, shopping, draftStore, today)
}
