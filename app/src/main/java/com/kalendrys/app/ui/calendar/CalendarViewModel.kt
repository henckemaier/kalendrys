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
import com.kalendrys.app.data.local.entity.RoutineOverride
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.ReminderRecurrence
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskTiming
import java.time.Instant
import java.time.LocalDate
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

    /** Um evento no dia: conclusão (do histórico) ou tarefa marcada para o dia. */
    data class Entry(
        val taskId: Long,
        val title: String?,
        /** Horário da conclusão ou do lembrete; null = tarefa sem horário (o dia todo). */
        val time: LocalTime?,
        val epochMillis: Long,
        val recurring: Boolean = false,
        val done: Boolean = false,
    )

    /** Um bloco de rotina no dia selecionado, com o início efetivo (honra uma remarcação). */
    data class RoutineEntry(val routine: Routine, val startMinute: Int, val done: Boolean)

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
        combine(
            repository.tasks,
            combine(routineRepository.routines, routineRepository.overrides) { routines, overrides -> routines to overrides },
            monthLogs,
            selectedDate,
            repository.lastCompletions,
        ) { tasks, routinesAndOverrides, logs, selected, lastCompletions ->
            val (routines, overrides) = routinesAndOverrides
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
                remindersByDay = tasksIn(logs.month, tasks, logs.tasks, lastCompletions, zone),
                routineDoneDays = logs.routines.mapTo(HashSet()) { LocalDate.ofEpochDay(it.epochDay) },
                routinesOnSelected = routines
                    .mapNotNull { routine ->
                        val ov = overrides[routine.id].orEmpty()
                        if (!RoutineSchedule.occursOn(routine, selected, zone, ov)) return@mapNotNull null
                        val start = RoutineSchedule.startOn(routine, selected, ov)
                        RoutineEntry(routine, start.hour * 60 + start.minute, done = routine.id in doneOnSelected)
                    }
                    .sortedWith(compareBy({ it.startMinute }, { it.routine.id })),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /**
     * Tarefas marcadas para cada dia do mês visível (as que se repetem, em cada dia em que
     * acontecem), com o horário do lembrete e se já foram feitas.
     */
    private fun tasksIn(
        month: YearMonth,
        tasks: List<Task>,
        monthLogs: List<CompletionLog>,
        lastCompletions: Map<Long, Long>,
        zone: ZoneId,
    ): Map<LocalDate, List<Entry>> {
        val doneByDay = CalendarCalculator.groupByDay(monthLogs, zone) { it.completedAt }
            .mapValues { (_, dayLogs) -> dayLogs.mapTo(HashSet()) { it.taskId } }
        val byDay = mutableMapOf<LocalDate, MutableList<Entry>>()
        for (task in tasks) {
            val due = TaskSchedule.dueDate(task) ?: continue
            val days = ReminderRecurrence.occurrencesBetween(
                due.atStartOfDay(),
                task.repeat,
                month.atDay(1),
                month.atEndOfMonth(),
                task.intervalEvery,
                task.intervalUnit,
                task.byWeekdays,
            )
            val repeats = task.repeat != ReminderRepeat.NONE
            for (day in days) {
                val time = TaskSchedule.reminderOn(task, day, zone)
                byDay.getOrPut(day) { mutableListOf() } += Entry(
                    taskId = task.id,
                    title = task.title,
                    time = time,
                    epochMillis = day.atTime(time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli(),
                    recurring = repeats,
                    done = if (repeats) {
                        doneByDay[day]?.contains(task.id) == true
                    } else {
                        TaskSchedule.isFinished(task, lastCompletions[task.id], zone)
                    },
                )
            }
        }
        // As sem horário (o dia todo) primeiro, depois por horário.
        return byDay.mapValues { (_, entries) -> entries.sortedWith(compareBy(nullsFirst()) { it.time }) }
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

    fun addTask(title: String, notes: String, timing: TaskTiming, location: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.addTask(title, notes, timing, location) }
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
