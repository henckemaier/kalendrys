package com.kalendrys.app.data.repository

import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TodayOverview
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

/** Retrato do dia (tarefas + rotinas) para o resumo das 8h e os widgets. */
class TodayRepository(
    private val taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
    private val shoppingRepository: ShoppingRepository,
    private val dates: () -> Flow<LocalDate> = ::currentDate,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: (ZoneId) -> LocalDateTime = { LocalDateTime.now(it) },
) {

    /** Cada incremento faz todo [observe] em andamento recalcular o retrato ([refresh]). */
    private val refreshes = MutableStateFlow(0L)

    /**
     * Recalcula agora, com o relógio de agora, todo [observe] em andamento. Sozinho, o "a seguir" só
     * avança pelo `delay` até o próximo horário, que conta tempo monotônico: com o processo congelado
     * (cached-app freezer), o aparelho em sono profundo ou o relógio de parede ajustado, ele acorda
     * tarde e o retrato fica parado num item que já começou. Numa sessão aberta do Glance, `update()`
     * só recompõe e não relê os dados (ver TasksWidget); por isso quem atualiza os widgets
     * ([com.kalendrys.app.widget.KalendrysWidgets.updateAll], chamado pelos alarmes) cutuca o fluxo
     * por aqui (F-1 da v1.6.1).
     */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    private data class Snapshot(
        val tasks: List<Task>,
        val taskLogs: List<CompletionLog>,
        val routines: List<Routine>,
        val routineLogs: List<RoutineCompletion>,
        val lastCompletions: Map<Long, Long>,
        val overrides: Map<Long, List<RoutineOverride>>,
        val listProgress: Map<Long, ShoppingProgress>,
    )

    /**
     * Recalculado a cada mudança no banco e também quando o tempo muda o retrato: o "a seguir"
     * vence (um lembrete toca, um bloco começa) ou o dia vira. Uma única data alimenta as duas
     * consultas de conclusões, para tarefas e rotinas nunca ficarem em dias diferentes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<TodayOverview> =
        dates().flatMapLatest { today ->
            val tomorrow = today.plusDays(1)
            combine(
                taskRepository.tasks,
                taskRepository.observeCompletionsBetween(today, tomorrow),
                routineRepository.scheduledRoutines,
                routineRepository.observeCompletionsBetween(today, tomorrow),
                // lastCompletions, overrides e progresso das listas juntos: o combine tipado vai só até 5 fontes.
                combine(
                    taskRepository.lastCompletions,
                    routineRepository.overrides,
                    shoppingRepository.observeProgressByList(),
                ) { last, overrides, progress -> Triple(last, overrides, progress) },
            ) { tasks, taskLogs, routines, routineLogs, extras ->
                Snapshot(tasks, taskLogs, routines, routineLogs, extras.first, extras.second, extras.third)
            }.let { snapshots -> combine(snapshots, refreshes) { snapshot, _ -> snapshot } }
                .transformLatest { snapshot ->
                while (true) {
                    val zone = zone()
                    val now = clock(zone)
                    val overview = TodayOverview.build(
                        tasks = snapshot.tasks,
                        completions = snapshot.taskLogs,
                        now = now,
                        zone = zone,
                        routines = snapshot.routines,
                        routineCompletions = snapshot.routineLogs,
                        lastCompletions = snapshot.lastCompletions,
                        overrides = snapshot.overrides,
                        listProgress = snapshot.listProgress,
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
