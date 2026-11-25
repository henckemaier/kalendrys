package com.kalendrys.app.testutil

import com.kalendrys.app.data.repository.SystemAppClock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.kalendrys.app.UndoOffers
import android.content.Context
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.ui.today.TodayViewModel
import java.time.DayOfWeek

/**
 * Repositórios da tela Hoje sobre o banco de teste, com alarmes e notificações falsos. [appScope] é o
 * escopo "do app" da oferta de Desfazer ([undoOffers], v1.9.0 E4); passe o do ViewModelTestDatabase
 * quando o teste concluir ou pular pela Hoje, para o trabalho dela terminar antes de o banco fechar.
 */
class TodayRepositories(
    context: Context,
    db: AppDatabase,
    appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val routines = RoutineRepository(
        db.routineDao(),
        db.routineCompletionDao(),
        db.routineOverrideDao(),
        FakeRoutineAlarms(context),
        FakeRoutineNotifications(context),
        db.shoppingItemDao(),
        InMemoryRoutineCatchUpMark(),
        clock = SystemAppClock,
    )
    val tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
    val shopping = ShoppingRepository(db, clock = SystemAppClock)

    /** O mesmo retrato do widget Resumo (fonte única das pendências da Hoje). */
    val today = TodayRepository(tasks, routines, shopping, clock = SystemAppClock)

    /** A oferta de Desfazer do app (concluir e pular pela Hoje, v1.9.0 E4). */
    val undoOffers = UndoOffers(tasks, routines, appScope, clock = SystemAppClock)

    fun viewModel(firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY) =
        TodayViewModel(routines, tasks, firstDayOfWeek, shopping, today, undoOffers, clock = SystemAppClock)
}
