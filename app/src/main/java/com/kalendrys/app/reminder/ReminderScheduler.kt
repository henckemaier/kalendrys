package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRecurrence
import java.time.ZoneId

/** Mantém no AlarmManager só a próxima ocorrência de cada lembrete. */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Agenda a próxima ocorrência depois de [after]; não faz nada se o lembrete já acabou. */
    fun schedule(task: Task, after: Long = System.currentTimeMillis()) {
        val triggerAt = ReminderRecurrence.nextTriggerMillis(
            reminderAt = task.reminderAt,
            repeat = task.reminderRepeat,
            now = after,
            zone = ZoneId.systemDefault(),
        ) ?: return
        alarmManager.setExactOrInexact(triggerAt, pendingIntent(task.id))
    }

    fun cancel(taskId: Long) {
        alarmManager.cancel(pendingIntent(taskId))
    }

    fun rescheduleAll(tasks: List<Task>) {
        tasks.forEach { schedule(it) }
    }

    // O título e a recorrência são lidos do banco quando o alarme toca, então o intent só leva o id.
    private fun pendingIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
