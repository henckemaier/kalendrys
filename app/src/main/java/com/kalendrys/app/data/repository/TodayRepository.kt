package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.TodayOverview
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest

/** Retrato do dia (tarefas + rotinas) para o resumo das 8h e os widgets. */
class TodayRepository(
    private val taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
) {

    private data class Snapshot(
        val tasks: List<Task>,
        val taskLogs: List<CompletionLog>,
        val routines: List<Routine>,
        val routineLogs: List<RoutineCompletion>,
        val lastCompletions: Map<Long, Long>,
    )

    /**
     * Recalculado a cada mudança no banco e também quando o tempo muda o retrato: o "a seguir"
     * vence (um lembrete toca, um bloco começa) ou o dia vira. Uma única data alimenta as duas
     * consultas de conclusões, para tarefas e rotinas nunca ficarem em dias diferentes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<TodayOverview> =
        currentDate().flatMapLatest { today ->
            val tomorrow = today.plusDays(1)
            combine(
                taskRepository.tasks,
                taskRepository.observeCompletionsBetween(today, tomorrow),
                routineRepository.scheduledRoutines,
                routineRepository.observeCompletionsBetween(today, tomorrow),
                taskRepository.lastCompletions,
                ::Snapshot,
            ).transformLatest { snapshot ->
                while (true) {
                    val zone = ZoneId.systemDefault()
                    val now = LocalDateTime.now(zone)
                    val overview = TodayOverview.build(
                        tasks = snapshot.tasks,
                        completions = snapshot.taskLogs,
                        now = now,
                        zone = zone,
                        routines = snapshot.routines,
                        routineCompletions = snapshot.routineLogs,
                        lastCompletions = snapshot.lastCompletions,
                    )
                    emit(overview)
                    // Depois da meia-noite quem recomeça tudo é o currentDate().
                    val next = overview.nextUp?.at?.takeIf { it.toLocalDate() == today } ?: awaitCancellation()
                    delay(Duration.between(now, next).toMillis().coerceAtLeast(0) + CHANGE_MARGIN_MS)
                }
            }
        }

    suspend fun current(): TodayOverview = observe().first()

    private companion object {
        const val CHANGE_MARGIN_MS = 1_000L
    }
}
