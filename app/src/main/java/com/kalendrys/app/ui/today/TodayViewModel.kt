package com.kalendrys.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.visibleProgressFor
import com.kalendrys.app.ui.components.firstDayOfWeek
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado da tela Hoje (ADR-0002, 14.4; v1.8.0, E2). Veio do antigo `RoutinesViewModel` com a mesma
 * lógica; o filtro por categoria saiu (vai para "Todas as rotinas") e entrou o painel de nova tarefa
 * do botão +, no mesmo molde do Calendário ([draftStore] é o rascunho único do app).
 */
class TodayViewModel(
    private val repository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val firstDayOfWeek: DayOfWeek,
    private val shoppingRepository: ShoppingRepository,
    private val draftStore: NewTaskDraftStore,
) : ViewModel() {

    /** Um ponto na faixa da semana: a cor do bloco e se ele foi feito. */
    data class DayDot(val color: RoutineColor, val done: Boolean)

    data class WeekDay(val date: LocalDate, val dots: List<DayDot>, val total: Int, val done: Int)

    /** Uma tarefa do dia sem horário, para a faixa colapsável no topo. */
    data class DayTask(val task: Task, val done: Boolean)

    data class UiState(
        val isLoaded: Boolean = false,
        val today: LocalDate = LocalDate.now(),
        val selectedDate: LocalDate = LocalDate.now(),
        val week: List<WeekDay> = emptyList(),
        val timeline: List<DayTimeline.Item> = emptyList(),
        /** Tarefas do dia sem horário; mostradas numa faixa, fora da trilha. */
        val untimedTasks: List<DayTask> = emptyList(),
        /** Alguma rotina ativa ou tarefa com data; sem nada a tela mostra o estado vazio. */
        val hasContent: Boolean = false,
        /** Progresso de cada lista de compras (listId → 3/8), para blocos e tarefas da trilha (P8). */
        val listProgress: Map<Long, ShoppingProgress> = emptyMap(),
    ) {
        val isShowingToday: Boolean get() = selectedDate == today

        /** "3/8" da lista do dono; null sem lista, com vínculo órfão ou lista vazia. */
        fun progressFor(listId: Long?): ShoppingProgress? = listProgress.visibleProgressFor(listId)

        /** Dias futuros ainda não podem ser marcados como feitos. */
        val canComplete: Boolean get() = !selectedDate.isAfter(today)
    }

    /** Conclusão de rotina que o snackbar oferece desfazer. */
    data class Completed(val routineId: Long, val date: LocalDate)

    private val selectedDate = MutableStateFlow(LocalDate.now())

    /** Última leitura das conclusões de tarefa da semana visível, para desfazer pela trilha. */
    @Volatile
    private var taskLogs: List<CompletionLog> = emptyList()

    /** As conclusões (rotina e tarefa) só da semana visível; a consulta muda quando a semana muda. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val weekCompletions = selectedDate
        .map { CalendarCalculator.weekStart(it, firstDayOfWeek) }
        .distinctUntilChanged()
        .flatMapLatest { start ->
            val end = start.plusDays(DAYS_IN_WEEK)
            combine(
                repository.observeCompletionsBetween(start, end),
                taskRepository.observeCompletionsBetween(start, end),
            ) { routineLogs, taskCompletions -> WeekLogs(start, routineLogs.groupLogs(), taskCompletions) }
        }

    private val dayState: Flow<UiState> =
        combine(
            combine(
                repository.routines,
                repository.scheduledRoutines,
                repository.overrides,
            ) { all, scheduled, overrides -> Triple(all, scheduled, overrides) },
            taskRepository.tasks,
            weekCompletions,
            selectedDate,
        ) { sources, tasks, weekLogs, selected ->
            val (routines, scheduled, overrides) = sources
            val (weekStart, routineDone, taskCompletions) = weekLogs
            // Logo após trocar de semana a consulta antiga ainda não foi refeita: espera a nova.
            if (selected.isBefore(weekStart) || !selected.isBefore(weekStart.plusDays(DAYS_IN_WEEK))) {
                return@combine null
            }
            taskLogs = taskCompletions
            val zone = ZoneId.systemDefault()
            val taskDoneByDay = taskCompletions
                .groupBy({ epochDayOf(it.completedAt, zone) }, { it.taskId })
                .mapValues { (_, ids) -> ids.toSet() }
            val routineDoneToday = routineDone[selected.toEpochDay()].orEmpty()
            val taskDoneToday = taskDoneByDay[selected.toEpochDay()].orEmpty()
            UiState(
                isLoaded = true,
                today = LocalDate.now(),
                selectedDate = selected,
                // Base da trilha = só as rotinas AGENDADAS (não pausadas). Pausada não entra na trilha
                // nem nos dots; continua em "Todas as rotinas" (gestão) para retomar.
                week = (0 until DAYS_IN_WEEK).map { offset ->
                    val date = weekStart.plusDays(offset)
                    weekDay(date, scheduled, routineDone[date.toEpochDay()].orEmpty(), zone, overrides)
                },
                // Pulados ficam visíveis, marcados (P5): a tela os esmaece com a ação de desfazer.
                timeline = DayTimeline.build(
                    scheduled,
                    routineDoneToday,
                    selected,
                    zone,
                    tasks,
                    taskDoneToday,
                    overrides,
                    includeSkipped = true,
                ),
                untimedTasks = tasks
                    .filter { it.reminderAt == null && TaskSchedule.occursOn(it, selected) }
                    .sortedBy { it.title.lowercase(AppLocale.LOCALE) }
                    .map { DayTask(it, it.id in taskDoneToday) },
                // O estado vazio olha TODAS as rotinas (inclusive pausadas): ter uma pausada não é "sem rotinas".
                hasContent = routines.any { it.archivedAt == null } || tasks.any { it.dueDate != null },
            )
        }.filterNotNull()

    // O progresso das listas entra por cima do retrato do dia: marcar um item não refaz a trilha.
    val uiState: StateFlow<UiState> =
        combine(dayState, shoppingRepository.observeProgressByList()) { day, progress ->
            day.copy(listProgress = progress)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val completedEvents = Channel<Completed>(Channel.BUFFERED)
    val completed: Flow<Completed> = completedEvents.receiveAsFlow()

    /**
     * Ocorrência pulada que o snackbar oferece desfazer (P5). [previous]: a exceção que o pular
     * substituiu naquele dia (ex.: uma remarcação), `null` se não havia; o [undoSkip] a recoloca.
     */
    data class Skipped(val routineId: Long, val date: LocalDate, val previous: RoutineOverride? = null)

    private val skippedEvents = Channel<Skipped>(Channel.BUFFERED)
    val skipped: Flow<Skipped> = skippedEvents.receiveAsFlow()

    /** Conclusão de tarefa (id do log) que o snackbar oferece desfazer. */
    private val taskCompletedEvents = Channel<Long>(Channel.BUFFERED)
    val taskCompleted: Flow<Long> = taskCompletedEvents.receiveAsFlow()

    // --- Painel de nova tarefa (botão +, P4): mesmo molde do Calendário ------------------------------

    /** Listas para o "Usar lista existente" do painel de nova tarefa. */
    val lists: StateFlow<List<ShoppingListSummary>> =
        shoppingRepository.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Rascunho único do painel de nova tarefa (compartilhado com Tarefas e Calendário); null = sem rascunho. */
    val newTaskDraft: StateFlow<NewTaskDraft?> = draftStore.draft

    init {
        // Lista escolhida no rascunho e excluída no meio some da escolha. O Flow do Room só emite a
        // lista de verdade (nunca um vazio provisório), então não apaga uma escolha válida.
        viewModelScope.launch {
            shoppingRepository.observeSummaries().collect { lists -> draftStore.keepListsIn(lists.mapTo(HashSet()) { it.id }) }
        }
    }

    /** O painel abriu sugerindo [date] (o dia selecionado); a data do rascunho só muda se o usuário ainda não a escolheu. */
    fun openNewTask(date: LocalDate?) = draftStore.open(date)

    fun updateNewTask(transform: (NewTaskDraft) -> NewTaskDraft) = draftStore.update(transform)

    /** Cancelar: descarta o rascunho. */
    fun cancelNewTask() {
        draftStore.discard()
    }

    /** Salvar: grava a tarefa do rascunho e o descarta. "Criar nova" cria a lista só ao salvar (ADR-0001, P2). */
    fun saveNewTask() {
        val draft = draftStore.discard() ?: return
        if (draft.title.isBlank()) return
        viewModelScope.launch {
            taskRepository.addTask(
                draft.title,
                draft.notes,
                draft.timing,
                draft.location,
                shoppingListId = shoppingRepository.resolveListId(draft.list),
            )
        }
    }

    // --- Dia e trilha ------------------------------------------------------------------------------

    private fun List<RoutineCompletion>.groupLogs(): Map<Long, Set<Long>> =
        groupBy({ it.epochDay }, { it.routineId }).mapValues { (_, ids) -> ids.toSet() }

    private fun weekDay(
        date: LocalDate,
        routines: List<Routine>,
        done: Set<Long>,
        zone: ZoneId,
        overrides: Map<Long, List<RoutineOverride>>,
    ): WeekDay {
        val blocks = routines
            .filter { RoutineSchedule.occursOn(it, date, zone, overrides[it.id].orEmpty()) }
            .sortedWith(compareBy({ it.startMinute }, { it.id }))
        return WeekDay(
            date = date,
            dots = blocks.take(MAX_DOTS).map { DayDot(RoutineColor.fromKey(it.color), it.id in done) },
            total = blocks.size,
            done = blocks.count { it.id in done },
        )
    }

    fun select(date: LocalDate) {
        selectedDate.value = date
    }

    fun goToToday() = select(LocalDate.now())

    fun previousWeek() = select(selectedDate.value.minusWeeks(1))

    fun nextWeek() = select(selectedDate.value.plusWeeks(1))

    /** Marca como feito (com "Desfazer" no snackbar) ou, se já estava feito, desmarca. */
    fun toggle(block: DayTimeline.Block) {
        val date = selectedDate.value
        // Pulado não se conclui: primeiro desfaz o pular.
        if (block.skipped || date.isAfter(LocalDate.now())) return
        viewModelScope.launch {
            if (block.done) {
                repository.uncomplete(block.routine.id, date)
            } else {
                repository.complete(block.routine.id, date)
                completedEvents.send(Completed(block.routine.id, date))
            }
        }
    }

    fun undo(completed: Completed) {
        viewModelScope.launch { repository.uncomplete(completed.routineId, completed.date) }
    }

    /**
     * Pular a ocorrência de [routineId] em [date] (P5): o MESMO [RoutineRepository.skipOccurrence] da
     * tela de exceção (reagenda os alarmes), e avisa a tela para oferecer Desfazer ([skipped]), com a
     * exceção que o pular substituiu. O bloco continua na trilha, marcado ([DayTimeline.Block.skipped]).
     */
    fun skip(routineId: Long, date: LocalDate) {
        viewModelScope.launch {
            val previous = repository.skipOccurrence(routineId, date.toEpochDay())
            skippedEvents.send(Skipped(routineId, date, previous))
        }
    }

    /**
     * Desfazer da MENSAGEM, logo depois de pular: devolve o dia ao que era antes — tira o pular e
     * recoloca a exceção anterior ([Skipped.previous], ex.: uma remarcação).
     */
    fun undoSkip(event: Skipped) {
        viewModelScope.launch {
            repository.restoreOccurrenceException(event.routineId, event.date.toEpochDay(), event.previous)
        }
    }

    /** "Desfazer pular" do BLOCO esmaecido, a qualquer momento: só tira o pular (a exceção do dia). */
    fun unskip(routineId: Long, date: LocalDate) {
        viewModelScope.launch { repository.clearOccurrenceException(routineId, date.toEpochDay()) }
    }

    /**
     * Concluir/desmarcar uma tarefa pela trilha, reusando o repositório de tarefas. Concluir
     * registra agora (com "Desfazer"); desmarcar desfaz a conclusão do dia selecionado.
     */
    fun toggleTask(task: Task, done: Boolean) {
        val date = selectedDate.value
        if (date.isAfter(LocalDate.now())) return
        viewModelScope.launch {
            if (done) {
                taskLogFor(task.id, date)?.let { taskRepository.undoCompletion(it) }
            } else {
                taskCompletedEvents.send(taskRepository.logCompletion(task.id))
            }
        }
    }

    fun undoTaskCompletion(logId: Long) {
        viewModelScope.launch { taskRepository.undoCompletion(logId) }
    }

    /**
     * O id do log de conclusão da tarefa [taskId] no dia [date] a desfazer, se houver: o MAIS RECENTE
     * (maior horário; empate pelo maior id). Com duas conclusões no dia (única adiantada, reaberta e
     * concluída de novo), desfazer a primeira devolveria a data errada (NB-1 da v1.8.0).
     */
    private fun taskLogFor(taskId: Long, date: LocalDate): Long? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return taskLogs
            .filter { it.taskId == taskId && it.completedAt in start until end }
            .maxWithOrNull(compareBy<CompletionLog>({ it.completedAt }, { it.id }))
            ?.id
    }

    private fun epochDayOf(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** Só para desestruturar o resultado da consulta da semana. */
    private data class WeekLogs(
        val weekStart: LocalDate,
        val routineDone: Map<Long, Set<Long>>,
        val taskCompletions: List<CompletionLog>,
    )

    companion object {
        private const val DAYS_IN_WEEK = 7L
        private const val MAX_DOTS = 4

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                TodayViewModel(
                    app.routineRepository,
                    app.repository,
                    firstDayOfWeek(),
                    app.shoppingRepository,
                    app.newTaskDraftStore,
                )
            }
        }
    }
}
