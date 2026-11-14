package com.kalendrys.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderSnooze
import com.kalendrys.app.domain.ShoppingProgress

/**
 * Monta e exibe a notificação de lembrete, com as ações "Concluir" e "Adiar" (e "Abrir lista"
 * quando a tarefa tem lista, ADR-0002 E-1). Compartilhada pelo [ReminderReceiver] (quando o alarme
 * toca) e pelo [ReminderActionReceiver] (ao reexibir depois de adiar), para o visual e as ações
 * ficarem num lugar só.
 */
object ReminderNotifications {

    /** Ações do lembrete, na ordem em que aparecem. */
    enum class Action { COMPLETE, SNOOZE, OPEN_LIST }

    /**
     * [listProgress] é o progresso da lista da tarefa; `null` quando ela não tem lista ou o vínculo
     * é órfão (lista excluída), e aí a notificação fica como sempre foi.
     */
    fun show(context: Context, task: Task, listProgress: ShoppingProgress? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            task.id.toInt(),
            AppLinks.openTask(context, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, KalendrysApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(ShoppingProgress.titleWithProgress(task.title, listProgress))
            .setContentText(task.notes.ifBlank { context.getString(R.string.reminder_text) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
        actionsFor(task, listProgress).forEach { action ->
            when (action) {
                Action.COMPLETE -> builder.addAction(
                    0,
                    context.getString(R.string.reminder_complete_action),
                    broadcast(context, ReminderActionReceiver.ACTION_COMPLETE, task.id, AppLinks.reminderComplete(task.id)),
                )
                Action.SNOOZE -> builder.addAction(
                    0,
                    context.getString(R.string.reminder_snooze_action, ReminderSnooze.DEFAULT_MINUTES),
                    broadcast(context, ReminderActionReceiver.ACTION_SNOOZE, task.id, AppLinks.reminderSnooze(task.id)),
                )
                // Abre o app direto na lista, com a tarefa como origem (oferta "Concluir tarefa").
                Action.OPEN_LIST -> builder.addAction(
                    0,
                    context.getString(R.string.reminder_open_list_action),
                    PendingIntent.getActivity(
                        context,
                        task.id.toInt(),
                        AppLinks.openList(context, checkNotNull(task.shoppingListId), task.id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        NotificationManagerCompat.from(context).notify(task.id.toInt(), builder.build())
    }

    /** Com lista (vínculo válido): Concluir, Adiar, Abrir lista. Sem lista ou órfã: Concluir, Adiar. */
    internal fun actionsFor(task: Task, listProgress: ShoppingProgress?): List<Action> =
        if (task.shoppingListId != null && listProgress != null) {
            listOf(Action.COMPLETE, Action.SNOOZE, Action.OPEN_LIST)
        } else {
            listOf(Action.COMPLETE, Action.SNOOZE)
        }

    // Uri próprio por tarefa e ação: extras não diferenciam PendingIntents, o Uri sim.
    private fun broadcast(context: Context, action: String, taskId: Long, data: Uri): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .setData(data)
                .putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
