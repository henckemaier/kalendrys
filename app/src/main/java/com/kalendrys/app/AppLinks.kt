package com.kalendrys.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Pedido vindo de fora do app (widget ou notificação) para abrir uma tela específica. */
sealed interface LaunchRequest {
    data class OpenTask(val taskId: Long) : LaunchRequest
    data object NewTask : LaunchRequest
    data class OpenRoutines(val date: LocalDate) : LaunchRequest
    data object OpenCalendar : LaunchRequest
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

    /** Identifica a ação "Concluir" de um bloco num dia (não abre o app). */
    fun routineOccurrence(routineId: Long, epochDay: Long): Uri =
        Uri.parse("$SCHEME://$HOST_ROUTINE/$routineId/$epochDay")

    fun parse(intent: Intent?): LaunchRequest? {
        val data = intent?.data?.takeIf { it.scheme == SCHEME } ?: return null
        return when (data.host) {
            HOST_TASK -> data.lastPathSegment?.toLongOrNull()?.let { LaunchRequest.OpenTask(it) }
            HOST_NEW_TASK -> LaunchRequest.NewTask
            HOST_ROUTINES -> LaunchRequest.OpenRoutines(parseDate(data.lastPathSegment) ?: LocalDate.now())
            HOST_CALENDAR -> LaunchRequest.OpenCalendar
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
