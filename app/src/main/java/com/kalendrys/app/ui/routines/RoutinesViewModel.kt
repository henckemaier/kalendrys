package com.kalendrys.app.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.ui.components.firstDayOfWeek
import java.time.DayOfWeek
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
    private val firstDayOfWeek: DayOfWeek,
) : ViewModel() {

    /** Um ponto na faixa da semana: a cor do bloco e se ele foi feito. */
    data class DayDot(val color: RoutineColor, val done: Boolean)

    data class WeekDay(val date: LocalDate, val dots: List<DayDot>, val total: Int, val done: Int)

    data class UiState(
        val isLoaded: Boolean = false,
        val today: LocalDate = LocalDate.now(),
        val selectedDate: LocalDate = LocalDate.now(),
        val week: List<WeekDay> = emptyList(),
        val timeline: List<DayTimeline.Item> = emptyList(),
        /** Alguma rotina ativa (não excluída); sem nenhuma a tela mostra o estado vazio. */
        val hasRoutines: Boolean = false,
    ) {
        val isShowingToday: Boolean get() = selectedDate == today

        /** Dias futuros ainda não podem ser marcados como feitos. */
        val canComplete: Boolean get() = !selectedDate.isAfter(today)
    }

    /** Conclusão que o snackbar oferece desfazer. */
    data class Completed(val routineId: Long, val date: LocalDate)

    private val selectedDate = MutableStateFlow(LocalDate.now())

    /** As conclusões só da semana visível; a consulta muda quando a semana muda, não a cada dia tocado. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val weekCompletions = selectedDate
        .map { CalendarCalculator.weekStart(it, firstDayOfWeek) }
        .distinctUntilChanged()
        .flatMapLatest { start ->
            repository.observeCompletionsBetween(start, start.plusDays(DAYS_IN_WEEK)).map { start to it }
        }

    val uiState: StateFlow<UiState> =
        combine(repository.routines, weekCompletions, selectedDate) { routines, (weekStart, logs), selected ->
            // Logo após trocar de semana a consulta antiga ainda não foi refeita: espera a nova.
            if (selected.isBefore(weekStart) || !selected.isBefore(weekStart.plusDays(DAYS_IN_WEEK))) {
                return@combine null
            }
            val zone = ZoneId.systemDefault()
            val doneByDay = logs.groupBy({ it.epochDay }, { it.routineId }).mapValues { (_, ids) -> ids.toSet() }
            UiState(
                isLoaded = true,
                today = LocalDate.now(),
                selectedDate = selected,
                week = (0 until DAYS_IN_WEEK).map { offset ->
                    val date = weekStart.plusDays(offset)
                    weekDay(date, routines, doneByDay[date.toEpochDay()].orEmpty(), zone)
                },
                timeline = DayTimeline.build(routines, doneByDay[selected.toEpochDay()].orEmpty(), selected, zone),
                hasRoutines = routines.any { it.archivedAt == null },
            )
        }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val completedEvents = Channel<Completed>(Channel.BUFFERED)
    val completed: Flow<Completed> = completedEvents.receiveAsFlow()

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

    companion object {
        private const val DAYS_IN_WEEK = 7L
        private const val MAX_DOTS = 4

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutinesViewModel(app.routineRepository, firstDayOfWeek())
            }
        }
    }
}
