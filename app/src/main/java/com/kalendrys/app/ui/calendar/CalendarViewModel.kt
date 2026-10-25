package com.kalendrys.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.ReminderRecurrence
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.RoutineSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CalendarViewModel(
    private val repository: TaskRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    /** Um evento no dia: conclusão (do histórico) ou ocorrência de lembrete (de uma tarefa). */
    data class Entry(
        val taskId: Long,
        val title: String?,
        val time: LocalTime,
        val epochMillis: Long,
        val recurring: Boolean = false,
    )

    /** Um bloco de rotina no dia selecionado. */
    data class RoutineEntry(val routine: Routine, val done: Boolean)

    data class UiState(
        val isLoaded: Boolean = false,
        val today: LocalDate = LocalDate.now(),
        val month: YearMonth = YearMonth.now(),
        val selectedDate: LocalDate = LocalDate.now(),
        val completedByDay: Map<LocalDate, List<Entry>> = emptyMap(),
        val remindersByDay: Map<LocalDate, List<Entry>> = emptyMap(),
        /** Dias do mês com algum bloco de rotina feito (contam no ponto de "concluídas"). */
        val routineDoneDays: Set<LocalDate> = emptySet(),
        val routinesOnSelected: List<RoutineEntry> = emptyList(),
    ) {
        val completedOnSelected: List<Entry> get() = completedByDay[selectedDate].orEmpty()
        val remindersOnSelected: List<Entry> get() = remindersByDay[selectedDate].orEmpty()
        val isShowingToday: Boolean get() = selectedDate == today && month == YearMonth.from(today)

        fun hasCompleted(date: LocalDate): Boolean = completedByDay.containsKey(date) || date in routineDoneDays
    }

    /** As conclusões de tarefas e de rotinas do mês visível, junto com o mês a que pertencem. */
    private data class MonthLogs(
        val month: YearMonth,
        val tasks: List<CompletionLog>,
        val routines: List<RoutineCompletion>,
    )

    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDate = MutableStateFlow(LocalDate.now())

    /** Só as conclusões do mês visível, emitidas junto com o mês a que pertencem (sem misturar meses). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthLogs = month.flatMapLatest { month ->
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        combine(
            repository.observeCompletionsBetween(start, end),
            routineRepository.observeCompletionsBetween(start, end),
        ) { tasks, routines -> MonthLogs(month, tasks, routines) }
    }

    val uiState: StateFlow<UiState> =
        combine(repository.tasks, routineRepository.routines, monthLogs, selectedDate) { tasks, routines, logs, selected ->
            val zone = ZoneId.systemDefault()
            val titles = tasks.associate { it.id to it.title }
            // Tarefas excluídas continuam no histórico; ficam sem título (title = null).
            val completed = CalendarCalculator.groupByDay(logs.tasks, zone) { it.completedAt }
                .mapValues { (_, dayLogs) ->
                    dayLogs.map { Entry(it.taskId, titles[it.taskId], timeOf(it.completedAt, zone), it.completedAt) }
                }
            val doneOnSelected = logs.routines
                .filter { it.epochDay == selected.toEpochDay() }
                .mapTo(HashSet()) { it.routineId }
            UiState(
                isLoaded = true,
                today = LocalDate.now(),
                month = logs.month,
                selectedDate = selected,
                completedByDay = completed,
                remindersByDay = remindersIn(logs.month, tasks, zone),
                routineDoneDays = logs.routines.mapTo(HashSet()) { LocalDate.ofEpochDay(it.epochDay) },
                routinesOnSelected = routines
                    .filter { RoutineSchedule.occursOn(it, selected, zone) }
                    .sortedWith(compareBy({ it.startMinute }, { it.id }))
                    .map { RoutineEntry(it, done = it.id in doneOnSelected) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Ocorrências de lembrete no mês visível; os recorrentes aparecem em cada dia em que tocam. */
    private fun remindersIn(month: YearMonth, tasks: List<Task>, zone: ZoneId): Map<LocalDate, List<Entry>> {
        val byDay = mutableMapOf<LocalDate, MutableList<Entry>>()
        for (task in tasks) {
            val reminderAt = task.reminderAt ?: continue
            val anchor = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminderAt), zone)
            val days = ReminderRecurrence.occurrencesBetween(anchor, task.reminderRepeat, month.atDay(1), month.atEndOfMonth())
            for (day in days) {
                val at = day.atTime(anchor.toLocalTime())
                byDay.getOrPut(day) { mutableListOf() } += Entry(
                    taskId = task.id,
                    title = task.title,
                    time = at.toLocalTime(),
                    epochMillis = at.atZone(zone).toInstant().toEpochMilli(),
                    recurring = task.reminderRepeat != ReminderRepeat.NONE,
                )
            }
        }
        return byDay.mapValues { (_, entries) -> entries.sortedBy { it.time } }
    }

    fun select(date: LocalDate) {
        selectedDate.value = date
        month.value = YearMonth.from(date)
    }

    /** Ao trocar de mês, seleciona hoje (se for o mês atual) ou o dia 1. */
    fun showMonth(target: YearMonth) {
        val today = LocalDate.now()
        month.value = target
        selectedDate.value = if (target == YearMonth.from(today)) today else target.atDay(1)
    }

    fun goToToday() = select(LocalDate.now())

    fun addTask(title: String, notes: String, reminderAt: Long?, repeat: ReminderRepeat) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.addTask(title, notes, reminderAt, repeat) }
    }

    private fun timeOf(epochMillis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                CalendarViewModel(app.repository, app.routineRepository)
            }
        }
    }
}
