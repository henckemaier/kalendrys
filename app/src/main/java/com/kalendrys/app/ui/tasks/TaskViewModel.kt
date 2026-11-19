package com.kalendrys.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskFiltering
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskSchedule.Status
import com.kalendrys.app.domain.visibleProgressFor
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class TaskViewModel(
    private val repository: TaskRepository,
    private val shoppingRepository: ShoppingRepository,
    private val undoOffers: UndoOffers,
) : ViewModel() {

    /**
     * Uma tarefa na lista e o dia a mostrar (a data dela ou, se repete, a próxima vez).
     * [listProgress] é o "3/8" da lista anexada; null sem lista, com vínculo órfão ou lista vazia.
     */
    data class Item(
        val task: Task,
        val date: LocalDate?,
        val reminderTime: LocalTime?,
        val listProgress: ShoppingProgress? = null,
    )

    /** Seções que os chips de filtro ligam/desligam. "Concluídas hoje" fica de fora (não é pendente). */
    enum class Filter { OVERDUE, TODAY, UPCOMING, NO_DATE }

    data class UiState(
        val today: LocalDate = LocalDate.now(),
        val overdue: List<Item> = emptyList(),
        val dueToday: List<Item> = emptyList(),
        val upcoming: List<Item> = emptyList(),
        val noDate: List<Item> = emptyList(),
        val doneToday: List<Item> = emptyList(),
        val isLoaded: Boolean = false,
        /** Havia ao menos uma tarefa antes de aplicar busca/filtros (decide qual estado vazio mostrar). */
        val hasTasks: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = overdue.isEmpty() && dueToday.isEmpty() && upcoming.isEmpty() && noDate.isEmpty() && doneToday.isEmpty()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow<Set<Filter>>(emptySet())
    val filters: StateFlow<Set<Filter>> = _filters.asStateFlow()

    /** Agrupamento/ordenação (caro): só recalcula quando os dados mudam, não a cada tecla da busca. */
    private val baseState: Flow<UiState> =
        combine(
            repository.tasks,
            repository.completionsToday,
            repository.lastCompletions,
            shoppingRepository.observeProgressByList(),
        ) { tasks, logsToday, last, progressByList ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val doneTodayIds = logsToday.mapTo(HashSet()) { it.taskId }
            val byStatus = tasks.groupBy { task ->
                TaskSchedule.status(
                    task = task,
                    today = today,
                    doneEver = TaskSchedule.isFinished(task, last[task.id], zone),
                    doneToday = task.id in doneTodayIds,
                )
            }
            fun items(status: Status, dateOf: (Task) -> LocalDate?) =
                byStatus[status].orEmpty().map {
                    Item(
                        task = it,
                        date = dateOf(it),
                        reminderTime = TaskSchedule.reminderOn(it, dateOf(it) ?: today, zone),
                        listProgress = progressByList.visibleProgressFor(it.shoppingListId),
                    )
                }
            UiState(
                today = today,
                overdue = items(Status.OVERDUE, TaskSchedule::dueDate).sortedBy { it.date },
                dueToday = items(Status.TODAY) { today }.sortedWith(compareBy(nullsLast()) { it.reminderTime }),
                upcoming = items(Status.UPCOMING) { TaskSchedule.nextOccurrence(it, today.plusDays(1)) }
                    .sortedWith(compareBy<Item, LocalDate?>(nullsLast()) { it.date }.thenBy(nullsLast()) { it.reminderTime }),
                noDate = items(Status.NO_DATE) { null },
                doneToday = items(Status.DONE_TODAY) { today },
                isLoaded = true,
            )
        }

    /** A lista como a tela a vê: base agrupada + busca/filtros (barato) aplicados por cima. */
    val uiState: StateFlow<UiState> =
        combine(baseState, _query, _filters) { base, query, filters ->
            base.filtered(query, filters)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun toggleFilter(filter: Filter) {
        _filters.update { if (filter in it) it - filter else it + filter }
    }

    /**
     * Círculo de concluir: concluir e oferecer o Desfazer é UMA operação do app (E5), no escopo dele,
     * para a mensagem sobreviver a girar a tela; quem mostra é o coletor do `KalendrysNavHost`.
     */
    fun complete(task: Task) {
        undoOffers.completeTask(task)
    }

    /** Deslize de excluir: o MESMO caminho do detalhe e do editor (NB-E3-C), oferta do app. */
    fun delete(task: Task) {
        undoOffers.deleteTask(task)
    }

    /**
     * Aplica busca e filtros à base agrupada. Com algum chip ligado, mostra só as seções pendentes
     * escolhidas e esconde "Concluídas hoje"; sem chip, mostra tudo. A busca atravessa as seções
     * visíveis. [hasTasks] guarda se havia algo antes de filtrar, para a tela escolher o vazio certo.
     */
    private fun UiState.filtered(query: String, filters: Set<Filter>): UiState {
        fun List<Item>.search() = if (query.isBlank()) this else filter { TaskFiltering.matches(it.task, query) }
        fun section(filter: Filter, items: List<Item>) =
            if (filters.isEmpty() || filter in filters) items.search() else emptyList()
        return copy(
            overdue = section(Filter.OVERDUE, overdue),
            dueToday = section(Filter.TODAY, dueToday),
            upcoming = section(Filter.UPCOMING, upcoming),
            noDate = section(Filter.NO_DATE, noDate),
            doneToday = if (filters.isEmpty()) doneToday.search() else emptyList(),
            hasTasks = !isEmpty,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                TaskViewModel(app.repository, app.shoppingRepository, app.undoOffers)
            }
        }
    }
}
