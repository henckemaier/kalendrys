package com.kalendrys.app.testutil

import android.content.Context
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.reminder.RoutineNotifications
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Fakes dos colaboradores de alarme/notificação: só registram as chamadas, sem tocar no
 * AlarmManager nem no NotificationManager reais. Os testes de repositório verificam que o banco
 * e os alarmes andam em sincronia conferindo o que foi agendado/cancelado.
 */
class FakeReminderScheduler(context: Context) : ReminderScheduler(context) {
    data class Scheduled(val task: Task, val after: Long)

    val scheduled = mutableListOf<Scheduled>()
    val cancelled = mutableListOf<Long>()
    val rescheduled = mutableListOf<List<Task>>()

    override fun schedule(task: Task, after: Long) {
        scheduled += Scheduled(task, after)
    }

    override fun cancel(taskId: Long) {
        cancelled += taskId
    }

    override fun rescheduleAll(tasks: List<Task>) {
        rescheduled += tasks
    }

    fun reset() {
        scheduled.clear()
        cancelled.clear()
        rescheduled.clear()
    }
}

class FakeRoutineAlarms(context: Context) : RoutineAlarms(context) {
    data class Call(
        val routines: List<Routine>,
        val after: LocalDateTime,
        val overridesByRoutine: Map<Long, List<RoutineOverride>>,
    )

    val calls = mutableListOf<Call>()

    override fun scheduleNext(
        routines: List<Routine>,
        after: LocalDateTime,
        overridesByRoutine: Map<Long, List<RoutineOverride>>,
    ) {
        calls += Call(routines, after, overridesByRoutine)
    }

    fun reset() {
        calls.clear()
    }
}

class FakeRoutineNotifications(context: Context) : RoutineNotifications(context) {
    val shown = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()

    override fun show(routine: Routine, date: LocalDate) {
        shown += routine.id
    }

    override fun cancel(routineId: Long) {
        cancelled += routineId
    }

    fun reset() {
        shown.clear()
        cancelled.clear()
    }
}
