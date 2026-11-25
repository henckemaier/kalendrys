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
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

/** Retrato do dia (tarefas + rotinas) para o resumo das 8h e os widgets. */
class TodayRepository(
    private val taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
    private val shoppingRepository: ShoppingRepository,
    /** O relógio do app (v1.10.0, 16.3): a data do retrato ([AppClock.dates]) e o agora dele, da mesma fonte. */
    private val clock: AppClock,
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

    /**
     * A ÚNICA fonte da data de [today] e de [observe] (O-4 da v1.8.0). Cada [refresh] relê a data:
     * a fonte recomeça com o relógio de agora (o laço da meia-noite do [AppClock.dates] mede a próxima
     * virada de novo), então um relógio ou fuso trocado com a Hoje aberta chega aqui no próximo
     * refresh (a troca de hora e de fuso já chama refresh pelo BootReceiver -> KalendrysWidgets.updateAll;
     * a Hoje pede um ao voltar para a frente). A mesma data não sai duas vezes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val days: Flow<LocalDate> = refreshes.flatMapLatest { clock.dates() }.distinctUntilChanged()

    /**
     * A data de hoje, da MESMA fonte que este retrato usa (emite de novo na virada da meia-noite e
     * num [refresh] que encontre outro dia). A tela Hoje a recebe por aqui para acompanhar o dia junto
     * com os números (NB-E3-B da v1.8.0).
     */
    val today: Flow<LocalDate> get() = days

    /** O agora do mesmo relógio do retrato (instante de uma conclusão feita pela Hoje). */
    fun now(): ZonedDateTime = clock.now()

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
        days.flatMapLatest { today ->
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
                    val zone = clock.zone()
                    val now = clock.nowLocal()
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
                    // Depois da meia-noite (ou num refresh em outro dia) quem recomeça tudo é [days].
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
