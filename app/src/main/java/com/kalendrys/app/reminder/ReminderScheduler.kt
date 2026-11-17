package com.kalendrys.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.kalendrys.app.data.local.entity.Task

/** Alarme de lembrete já calculado: a tarefa e o instante em que ele deve tocar. */
data class ReminderAlarm(val task: Task, val triggerAt: Long)

/**
 * Mantém no AlarmManager só a próxima ocorrência de cada lembrete.
 *
 * `open` para os testes do [com.kalendrys.app.data.repository.TaskRepository] poderem usar um
 * fake que apenas registra as chamadas, sem depender do AlarmManager real.
 */
open class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Marca o alarme do lembrete de [task] no instante [triggerAt] (epoch millis). Quem decide o
     * instante é o TaskRepository (ADR-0002, 14.3): a próxima ocorrência que será ENTREGUE, a mesma
     * conta do diagnóstico de alarmes. Aqui só se marca; o mesmo PendingIntent substitui o anterior.
     */
    open fun schedule(task: Task, triggerAt: Long) {
        alarmManager.setAlarmClockOrInexact(context, triggerAt, pendingIntent(task.id))
    }

    open fun cancel(taskId: Long) {
        alarmManager.cancel(pendingIntent(taskId))
    }

    /**
     * Tira da tela a notificação de lembrete JÁ EXIBIDA da tarefa (mesmo id do
     * [ReminderNotifications.show]), sem mexer no alarme: a próxima ocorrência de uma tarefa
     * repetida continua agendada. Sem notificação exibida, não faz nada.
     */
    open fun dismissNotification(taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }

    /** Marca de uma vez os alarmes já calculados pelo TaskRepository (catch-up e restauro). */
    open fun rescheduleAll(alarms: List<ReminderAlarm>) {
        alarms.forEach { schedule(it.task, it.triggerAt) }
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
