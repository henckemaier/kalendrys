package com.kalendrys.app.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kalendrys.app.AppLinks
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.RoutineSnooze
import com.kalendrys.app.domain.ShoppingProgress
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Aviso de início de um bloco de rotina, com as ações Concluir, Pular (ou Abrir lista) e Adiar. */
open class RoutineNotifications(private val context: Context) {

    /** [listProgress]: progresso da lista do bloco (`null` sem lista ou com vínculo órfão). Só muda o título ("Feira · 3/8"). */
    open fun show(
        routine: Routine,
        date: LocalDate,
        overrides: List<RoutineOverride> = emptyList(),
        listProgress: ShoppingProgress? = null,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val requestCode = routine.id.toInt()
        val tapIntent = PendingIntent.getActivity(
            context,
            requestCode,
            AppLinks.openRoutines(context, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KalendrysApp.ROUTINE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(ShoppingProgress.titleWithProgress(routine.title, listProgress))
            .setContentText(timeLabel(routine, date, overrides))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            // Some sozinha quando o bloco termina; sem duração, depois de uma hora.
            .setTimeoutAfter(maxOf(routine.durationMinutes, NO_DURATION_TIMEOUT_MINUTES) * 60_000L)
        actionsFor(routine, listProgress).forEach { action ->
            when (action) {
                Action.COMPLETE -> notification.addAction(
                    0,
                    context.getString(R.string.routine_complete_action),
                    action(context, RoutineAlarmReceiver.ACTION_COMPLETE, routine.id, date, AppLinks.routineOccurrence(routine.id, date.toEpochDay())),
                )
                Action.SKIP -> notification.addAction(
                    0,
                    context.getString(R.string.routine_skip_action),
                    action(context, RoutineAlarmReceiver.ACTION_SKIP, routine.id, date, AppLinks.routineSkip(routine.id, date.toEpochDay())),
                )
                // Abre a lista direto, sem tarefa de origem: rotina não recebe a oferta P3.
                Action.OPEN_LIST -> notification.addAction(
                    0,
                    context.getString(R.string.reminder_open_list_action),
                    PendingIntent.getActivity(
                        context,
                        requestCode,
                        AppLinks.openList(context, checkNotNull(routine.shoppingListId)),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                Action.SNOOZE -> notification.addAction(
                    0,
                    context.getString(R.string.routine_snooze_action, RoutineSnooze.DEFAULT_MINUTES),
                    action(context, RoutineAlarmReceiver.ACTION_SNOOZE, routine.id, date, AppLinks.routineSnooze(routine.id, date.toEpochDay())),
                )
            }
        }
        NotificationManagerCompat.from(context).notify(TAG, requestCode, notification.build())
    }

    open fun cancel(routineId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG, routineId.toInt())
    }

    // Uri próprio por rotina, dia e ação: extras não diferenciam PendingIntents, o Uri sim.
    private fun action(
        context: Context,
        action: String,
        routineId: Long,
        date: LocalDate,
        data: android.net.Uri,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            routineId.toInt(),
            Intent(context, RoutineAlarmReceiver::class.java)
                .setAction(action)
                .setData(data)
                .putExtra(RoutineAlarmReceiver.EXTRA_ROUTINE_ID, routineId)
                .putExtra(RoutineAlarmReceiver.EXTRA_EPOCH_DAY, date.toEpochDay()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ações do aviso, na ordem em que aparecem. */
    enum class Action { COMPLETE, SKIP, OPEN_LIST, SNOOZE }

    companion object {
        /**
         * Ações do aviso de bloco (ADR-0002, E-1, aprovada pelo usuário). Bloco com lista válida
         * (lista existente e rotina não arquivada): Concluir, Abrir lista, Adiar. O Android mostra no
         * máximo 3 ações, então "Pular" sai SÓ desses avisos e continua disponível no app. Sem lista
         * ou com vínculo órfão ([listProgress] `null`): Concluir, Pular, Adiar, como antes.
         */
        internal fun actionsFor(routine: Routine, listProgress: ShoppingProgress?): List<Action> =
            if (routine.shoppingListId != null && listProgress != null && routine.archivedAt == null) {
                listOf(Action.COMPLETE, Action.OPEN_LIST, Action.SNOOZE)
            } else {
                listOf(Action.COMPLETE, Action.SKIP, Action.SNOOZE)
            }

        // Tag própria: os ids das rotinas e das tarefas começam ambos em 1.
        private const val TAG = "routine"
        private const val NO_DURATION_TIMEOUT_MINUTES = 60
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Texto do aviso: horário EFETIVO da ocorrência do dia e a duração. Honra um `RESCHEDULE`
         * do dia resolvendo pelo [RoutineSchedule.startOn] — o mesmo caminho que a timeline e o
         * resumo já usam —, não pelo [Routine.startMinute] base. Sem exceção, é o horário base.
         */
        internal fun timeLabel(
            routine: Routine,
            date: LocalDate,
            overrides: List<RoutineOverride> = emptyList(),
        ): String {
            val start = RoutineSchedule.startOn(routine, date, overrides).toLocalTime()
            if (routine.durationMinutes == 0) return start.format(TIME)
            val endMinute = start.hour * 60 + start.minute + routine.durationMinutes
            return "${start.format(TIME)}–${timeOf(endMinute)} · ${DurationFormat.format(routine.durationMinutes)}"
        }

        private fun timeOf(minute: Int): String = LocalTime.of(minute % 1440 / 60, minute % 60).format(TIME)
    }
}
