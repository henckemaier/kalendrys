package com.kalendrys.app.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.TaskSchedule
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

class RoutinesViewModel(
    private val repository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val categoryRepository: RoutineCategoryRepository,
    private val firstDayOfWeek: DayOfWeek,
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
        /** Categorias existentes, para os chips de filtro (vazio = tela não mostra o filtro). */
        val categories: List<RoutineCategory> = emptyList(),
        /** Categoria do filtro ativo, ou null para "todas". */
        val selectedCategoryId: Long? = null,
    ) {
        val isShowingToday: Boolean get() = selectedDate == today

        /** Dias futuros ainda não podem ser marcados como feitos. */
        val canComplete: Boolean get() = !selectedDate.isAfter(today)
    }

    /** Conclusão de rotina que o snackbar oferece desfazer. */
    data class Completed(val routineId: Long, val date: LocalDate)

    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val selectedCategoryId = MutableStateFlow<Long?>(null)

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

    val uiState: StateFlow<UiState> =
        combine(
            combine(repository.routines, repository.scheduledRoutines) { all, scheduled -> all to scheduled },
            taskRepository.tasks,
            weekCompletions,
            categoryRepository.categories,
            combine(selectedDate, selectedCategoryId) { date, category -> date to category },
        ) { sources, tasks, weekLogs, categories, selection ->
            val (routines, scheduled) = sources
            val (weekStart, routineDone, taskCompletions) = weekLogs
            val (selected, rawCategory) = selection
            // Logo após trocar de semana a consulta antiga ainda não foi refeita: espera a nova.
            if (selected.isBefore(weekStart) || !selected.isBefore(weekStart.plusDays(DAYS_IN_WEEK))) {
                return@combine null
            }
            // Se a categoria do filtro foi excluída, volta sozinho para "todas".
            val category = rawCategory?.takeIf { id -> categories.any { it.id == id } }
            taskLogs = taskCompletions
            val zone = ZoneId.systemDefault()
            val taskDoneByDay = taskCompletions
                .groupBy({ epochDayOf(it.completedAt, zone) }, { it.taskId })
                .mapValues { (_, ids) -> ids.toSet() }
            val routineDoneToday = routineDone[selected.toEpochDay()].orEmpty()
            val taskDoneToday = taskDoneByDay[selected.toEpochDay()].orEmpty()
            // Base da trilha = só as rotinas AGENDADAS (não pausadas); o filtro de categoria, quando
            // ativo, restringe mais ainda. As tarefas (sem categoria) saem com filtro ativo.
            val visibleRoutines =
                if (category == null) scheduled else scheduled.filter { it.categoryId == category }
            val visibleTasks = if (category == null) tasks else emptyList()
            UiState(
                isLoaded = true,
                today = LocalDate.now(),
                selectedDate = selected,
                // Pausada não entra na trilha nem nos dots; continua no calendário (gestão) para retomar.
                week = (0 until DAYS_IN_WEEK).map { offset ->
                    val date = weekStart.plusDays(offset)
                    weekDay(date, visibleRoutines, routineDone[date.toEpochDay()].orEmpty(), zone)
                },
                timeline = DayTimeline.build(visibleRoutines, routineDoneToday, selected, zone, visibleTasks, taskDoneToday),
                untimedTasks = visibleTasks
                    .filter { it.reminderAt == null && TaskSchedule.occursOn(it, selected) }
                    .sortedBy { it.title.lowercase() }
                    .map { DayTask(it, it.id in taskDoneToday) },
                // O estado vazio olha TODAS as rotinas (inclusive pausadas): ter uma pausada não é "sem rotinas".
                hasContent = routines.any { it.archivedAt == null } || tasks.any { it.dueDate != null },
                categories = categories,
                selectedCategoryId = category,
            )
        }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val completedEvents = Channel<Completed>(Channel.BUFFERED)
    val completed: Flow<Completed> = completedEvents.receiveAsFlow()

    /** Conclusão de tarefa (id do log) que o snackbar oferece desfazer. */
    private val taskCompletedEvents = Channel<Long>(Channel.BUFFERED)
    val taskCompleted: Flow<Long> = taskCompletedEvents.receiveAsFlow()

    private fun List<RoutineCompletion>.groupLogs(): Map<Long, Set<Long>> =
        groupBy({ it.epochDay }, { it.routineId }).mapValues { (_, ids) -> ids.toSet() }

    private fun weekDay(date: LocalDate, routines: List<Routine>, done: Set<Long>, zone: ZoneId): WeekDay {
        val blocks = routines
            .filter { RoutineSchedule.occursOn(it, date, zone) }
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

    /** Filtra a trilha por categoria; null mostra todas (rotinas + tarefas). */
    fun selectCategory(categoryId: Long?) {
        selectedCategoryId.value = categoryId
    }

    /** Marca como feito (com "Desfazer" no snackbar) ou, se já estava feito, desmarca. */
    fun toggle(block: DayTimeline.Block) {
        val date = selectedDate.value
        if (date.isAfter(LocalDate.now())) return
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

    /** O id do log de conclusão da tarefa [taskId] no dia [date], se houver, para desfazer. */
    private fun taskLogFor(taskId: Long, date: LocalDate): Long? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return taskLogs.firstOrNull { it.taskId == taskId && it.completedAt in start until end }?.id
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
                RoutinesViewModel(app.routineRepository, app.repository, app.routineCategoryRepository, firstDayOfWeek())
            }
        }
    }
}
