package com.kalendrys.app.testutil

import android.content.Context
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.reminder.ReminderAlarm
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
open class FakeReminderScheduler(context: Context) : ReminderScheduler(context) {
    data class Scheduled(val task: Task, val triggerAt: Long)

    val scheduled = mutableListOf<Scheduled>()
    val cancelled = mutableListOf<Long>()
    val dismissed = mutableListOf<Long>()
    val rescheduled = mutableListOf<List<Task>>()
    val rescheduledAlarms = mutableListOf<ReminderAlarm>()

    override fun schedule(task: Task, triggerAt: Long) {
        scheduled += Scheduled(task, triggerAt)
    }

    override fun cancel(taskId: Long) {
        cancelled += taskId
    }

    override fun dismissNotification(taskId: Long) {
        dismissed += taskId
    }

    override fun rescheduleAll(alarms: List<ReminderAlarm>) {
        rescheduled += alarms.map { it.task }
        rescheduledAlarms += alarms
    }

    fun reset() {
        scheduled.clear()
        cancelled.clear()
        dismissed.clear()
        rescheduled.clear()
        rescheduledAlarms.clear()
    }
}

/**
 * Registra cada agendamento dos dois alarmes das rotinas (v1.9.1, E5-B): o horário do AVISO e o do
 * alarme silencioso dos WIDGETS, como o repositório os calculou (nulo = cancelado).
 */
class FakeRoutineAlarms(context: Context) : RoutineAlarms(context) {
    data class Call(val remindAt: LocalDateTime?, val refreshAt: LocalDateTime?)

    val calls = mutableListOf<Call>()

    override fun schedule(remindAt: LocalDateTime?, refreshAt: LocalDateTime?) {
        calls += Call(remindAt, refreshAt)
    }

    fun reset() {
        calls.clear()
    }
}

class FakeRoutineNotifications(context: Context) : RoutineNotifications(context) {
    val shown = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()

    override fun show(routine: Routine, date: LocalDate, overrides: List<RoutineOverride>, listProgress: ShoppingProgress?) {
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
