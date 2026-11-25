package com.kalendrys.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Pedido vindo de fora do app (widget, notificação ou atalho) para abrir uma tela específica. */
sealed interface LaunchRequest {
    data class OpenTask(val taskId: Long) : LaunchRequest

    /** Aba Tarefas com o painel de nova tarefa aberto (widget e atalho "Nova tarefa"). */
    data object NewTask : LaunchRequest
    /** [date] nulo = hoje, resolvido por quem abre (com o relógio do app). */
    data class OpenRoutines(val date: LocalDate?) : LaunchRequest
    data object OpenCalendar : LaunchRequest

    /** Aba Listas e, sobre ela, a tela da lista. [taskId] é a tarefa de origem (oferta P3), se houver. */
    data class OpenList(val listId: Long, val taskId: Long? = null) : LaunchRequest

    /** A própria aba Listas, sem lista aberta por cima (atalho "Listas", ADR-0002, 12.6). */
    data object OpenLists : LaunchRequest
}

/**
 * Intents que abrem o app em um ponto específico. Cada um tem um Uri próprio para que os
 * PendingIntents de tarefas diferentes não se confundam (extras não diferenciam PendingIntents).
 */
object AppLinks {

    private const val SCHEME = "kalendrys"
    private const val HOST_TASK = "task"
    private const val HOST_NEW_TASK = "new-task"
    private const val HOST_ROUTINES = "routines"
    private const val HOST_ROUTINE = "routine"
    private const val HOST_CALENDAR = "calendar"
    private const val HOST_LIST = "list"
    private const val HOST_LISTS = "lists"
    private const val PARAM_TASK_ID = "taskId"
    private const val HOST_REMINDER_COMPLETE = "reminder-complete"
    private const val HOST_REMINDER_SNOOZE = "reminder-snooze"
    private const val HOST_REMINDER_SNOOZE_FIRE = "reminder-snooze-fire"
    private const val HOST_ROUTINE_SKIP = "routine-skip"
    private const val HOST_ROUTINE_SNOOZE = "routine-snooze"
    private const val HOST_ROUTINE_SNOOZE_FIRE = "routine-snooze-fire"

    fun openApp(context: Context): Intent = baseIntent(context)

    fun openTask(context: Context, taskId: Long): Intent =
        baseIntent(context).setData(Uri.parse("$SCHEME://$HOST_TASK/$taskId"))

    fun newTask(context: Context): Intent =
        baseIntent(context).setData(Uri.parse("$SCHEME://$HOST_NEW_TASK"))

    /** A aba de rotinas no dia [date] (ex.: ao tocar no aviso de início de um bloco). */
    fun openRoutines(context: Context, date: LocalDate): Intent =
        baseIntent(context).setData(Uri.parse("$SCHEME://$HOST_ROUTINES/$date"))

    /** A aba do calendário (ex.: toque no widget de calendário). */
    fun openCalendar(context: Context): Intent =
        baseIntent(context).setData(Uri.parse("$SCHEME://$HOST_CALENDAR"))

    /**
     * A tela de uma lista sobre a aba Listas (ADR-0002, E-1): `kalendrys://list/{listId}?taskId={id}`.
     * [taskId] é a tarefa de origem, que alimenta a oferta "Concluir tarefa" (P3).
     */
    fun openList(context: Context, listId: Long, taskId: Long? = null): Intent =
        baseIntent(context).setData(listUri(listId, taskId))

    /** A aba Listas (atalho do app): `kalendrys://lists`. */
    fun openLists(context: Context): Intent =
        baseIntent(context).setData(Uri.parse("$SCHEME://$HOST_LISTS"))

    internal fun listUri(listId: Long, taskId: Long? = null): Uri =
        Uri.parse(
            if (taskId == null) "$SCHEME://$HOST_LIST/$listId" else "$SCHEME://$HOST_LIST/$listId?$PARAM_TASK_ID=$taskId",
        )

    /** Identifica a ação "Concluir" de um bloco num dia (não abre o app). */
    fun routineOccurrence(routineId: Long, epochDay: Long): Uri =
        Uri.parse("$SCHEME://$HOST_ROUTINE/$routineId/$epochDay")

    /** Uris próprios das ações "Pular"/"Adiar" do aviso de bloco (não abrem o app; dão unicidade ao PendingIntent). */
    fun routineSkip(routineId: Long, epochDay: Long): Uri =
        Uri.parse("$SCHEME://$HOST_ROUTINE_SKIP/$routineId/$epochDay")

    fun routineSnooze(routineId: Long, epochDay: Long): Uri =
        Uri.parse("$SCHEME://$HOST_ROUTINE_SNOOZE/$routineId/$epochDay")

    fun routineSnoozeFire(routineId: Long, epochDay: Long): Uri =
        Uri.parse("$SCHEME://$HOST_ROUTINE_SNOOZE_FIRE/$routineId/$epochDay")

    /** Uris próprios das ações da notificação de lembrete (não abrem o app; dão unicidade ao PendingIntent). */
    fun reminderComplete(taskId: Long): Uri = Uri.parse("$SCHEME://$HOST_REMINDER_COMPLETE/$taskId")

    fun reminderSnooze(taskId: Long): Uri = Uri.parse("$SCHEME://$HOST_REMINDER_SNOOZE/$taskId")

    fun reminderSnoozeFire(taskId: Long): Uri = Uri.parse("$SCHEME://$HOST_REMINDER_SNOOZE_FIRE/$taskId")

    fun parse(intent: Intent?): LaunchRequest? {
        val data = intent?.data?.takeIf { it.scheme == SCHEME } ?: return null
        return when (data.host) {
            HOST_TASK -> data.lastPathSegment?.toLongOrNull()?.let { LaunchRequest.OpenTask(it) }
            HOST_NEW_TASK -> LaunchRequest.NewTask
            HOST_ROUTINES -> LaunchRequest.OpenRoutines(parseDate(data.lastPathSegment))
            HOST_CALENDAR -> LaunchRequest.OpenCalendar
            HOST_LIST -> data.lastPathSegment?.toLongOrNull()?.let { listId ->
                LaunchRequest.OpenList(listId, data.getQueryParameter(PARAM_TASK_ID)?.toLongOrNull())
            }
            HOST_LISTS -> LaunchRequest.OpenLists
            else -> null
        }
    }

    private fun parseDate(text: String?): LocalDate? =
        try {
            text?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        }

    // SINGLE_TOP (com launchMode singleTop) entrega o pedido em onNewIntent se o app já estiver aberto.
    private fun baseIntent(context: Context) =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
