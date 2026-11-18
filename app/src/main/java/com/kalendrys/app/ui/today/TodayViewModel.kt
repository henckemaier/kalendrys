package com.kalendrys.app.ui.today

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
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineSchedule
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TodayOverview
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado da tela Hoje (ADR-0002, 14.4; v1.8.0, E2). Veio do antigo `RoutinesViewModel` com a mesma
 * lógica; o filtro por categoria saiu (vai para "Todas as rotinas"). O botão + pede o
 * painel de nova tarefa, que mora no topo da navegação (E6).
 */
class TodayViewModel(
    private val repository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val firstDayOfWeek: DayOfWeek,
    private val shoppingRepository: ShoppingRepository,
    /** Fonte única das pendências de hoje (E3): o mesmo retrato do widget Resumo e do resumo diário. */
    private val todayRepository: TodayRepository,
) : ViewModel() {

    /** Um ponto na faixa da semana: a cor do bloco e se ele foi feito. */
    data class DayDot(val color: RoutineColor, val done: Boolean)

    data class WeekDay(val date: LocalDate, val dots: List<DayDot>, val total: Int, val done: Int)

    /** Uma tarefa de uma das seções (fora da trilha), com o estado de feita no dia mostrado. */
    data class DayTask(val task: Task, val done: Boolean)

    data class UiState(
        val isLoaded: Boolean = false,
        // Valores de antes de carregar (isLoaded = false); o hoje de verdade vem do TodayRepository.
        val today: LocalDate = LocalDate.MIN,
        val selectedDate: LocalDate = LocalDate.MIN,
        val week: List<WeekDay> = emptyList(),
        val timeline: List<DayTimeline.Item> = emptyList(),
        /**
         * O retrato de hoje ([TodayOverview], o mesmo do widget Resumo), só quando o dia mostrado é
         * hoje: progresso, próximo item e as pendências das seções saem dele, sem recalcular na tela.
         */
        val overview: TodayOverview? = null,
        /** Pendentes atrasadas (só hoje; do [overview]). */
        val overdueTasks: List<DayTask> = emptyList(),
        /**
         * Pendentes do dia SEM horário (as com horário ficam só na trilha). Hoje: do [overview]; em
         * outro dia: as tarefas daquele dia ainda não feitas nele.
         */
        val dayTasks: List<DayTask> = emptyList(),
        /** Pendentes sem data (só hoje; do [overview]); seção recolhida. */
        val noDateTasks: List<DayTask> = emptyList(),
        /**
         * Concluídas no dia mostrado (pela data do REGISTRO), fora as já riscadas na trilha; cada uma
         * pode ser desmarcada. Tarefa excluída não entra. Seção recolhida.
         */
        val completedTasks: List<DayTask> = emptyList(),
        /** Alguma rotina ativa ou tarefa; sem nada a tela mostra o estado vazio (o primeiro uso). */
        val hasContent: Boolean = false,
        /** Progresso de cada lista de compras (listId → 3/8), para blocos e tarefas da trilha (P8). */
        val listProgress: Map<Long, ShoppingProgress> = emptyMap(),
    ) {
        val isShowingToday: Boolean get() = selectedDate == today

        /** "3/8" da lista do dono; null sem lista, com vínculo órfão ou lista vazia. */
        fun progressFor(listId: Long?): ShoppingProgress? = listProgress.visibleProgressFor(listId)

        /** Dias futuros ainda não podem ser marcados como feitos. */
        val canComplete: Boolean get() = !selectedDate.isAfter(today)

        /** Feitos hoje (tarefas e blocos), pelos números do [overview]. */
        val doneToday: Int? get() = overview?.let { it.doneCount + it.routines.count { block -> block.done } }

        /** Pendentes hoje (tarefas e blocos): o mesmo número do widget Resumo ([TodayOverview.pendingCount]). */
        val pendingToday: Int? get() = overview?.pendingCount
    }

    /** Conclusão de rotina que o snackbar oferece desfazer. */
    data class Completed(val routineId: Long, val date: LocalDate)

    /**
     * O que a Hoje mostra (NB-E3-B): ACOMPANHANDO HOJE (padrão) ou UM DIA ESCOLHIDO. Tocar em Hoje, ou
     * escolher hoje na faixa, volta a acompanhar. Na virada da meia-noite: quem acompanha vai sozinho
     * para o dia novo; um dia escolhido que virou hoje volta a acompanhar; um dia passado fica onde está.
     */
    private sealed interface Selection {
        data object FollowingToday : Selection

        data class Chosen(val date: LocalDate) : Selection
    }

    /** O dia mostrado e o hoje do mesmo instante. */
    private data class Day(val today: LocalDate, val selected: LocalDate)

    private val selection = MutableStateFlow<Selection>(Selection.FollowingToday)

    /** O último [Day] visto pela tela, para as ações (tocar, pular, concluir) usarem o mesmo hoje. */
    @Volatile
    private var lastDay: Day? = null

    /**
     * Hoje vem da MESMA fonte de datas do [TodayRepository] (que avisa a troca de dia); nada de relógio
     * direto aqui. Só corre enquanto a tela observa (a fonte espera a meia-noite com `delay`). Um dia
     * escolhido que é hoje (escolhido agora, ou que virou hoje na meia-noite) volta a acompanhar.
     */
    private val day: Flow<Day> =
        combine(selection, todayRepository.today) { sel, now ->
            if (sel is Selection.Chosen && sel.date == now) {
                selection.compareAndSet(sel, Selection.FollowingToday)
                Day(now, now)
            } else {
                Day(now, if (sel is Selection.Chosen) sel.date else now)
            }
        }.distinctUntilChanged()
            .onEach { lastDay = it }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val selectedDate: Flow<LocalDate> = day.map { it.selected }.distinctUntilChanged()

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
            day,
            todayRepository.observe(),
        ) { sources, tasks, weekLogs, shown, todayOverview ->
            val selected = shown.selected
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
            val today = shown.today
            // Pulados ficam visíveis, marcados (P5): a tela os esmaece com a ação de desfazer.
            val timeline = DayTimeline.build(
                scheduled,
                routineDoneToday,
                selected,
                zone,
                tasks,
                taskDoneToday,
                overrides,
                includeSkipped = true,
            )
            val byId = tasks.associateBy { it.id }
            // Só hoje, e só o retrato do mesmo dia (na virada da meia-noite os dois andam separados por um instante).
            val overview = todayOverview.takeIf { selected == today && it.date == selected }
            val sections = if (overview != null) {
                pendingSections(overview, byId)
            } else {
                PendingSections(
                    day = tasks
                        .filter { it.reminderAt == null && TaskSchedule.occursOn(it, selected) && it.id !in taskDoneToday }
                        .sortedBy { it.title.lowercase(AppLocale.LOCALE) }
                        .map { DayTask(it, done = false) },
                )
            }
            // Concluídas pela data do REGISTRO no dia mostrado; as já riscadas na trilha não se repetem.
            val struckInTrail = timeline
                .filterIsInstance<DayTimeline.TaskItem>()
                .filter { it.done }
                .mapTo(HashSet()) { it.task.id }
            UiState(
                isLoaded = true,
                today = today,
                selectedDate = selected,
                // Base da trilha = só as rotinas AGENDADAS (não pausadas). Pausada não entra na trilha
                // nem nos dots; continua em "Todas as rotinas" (gestão) para retomar.
                week = (0 until DAYS_IN_WEEK).map { offset ->
                    val date = weekStart.plusDays(offset)
                    weekDay(date, scheduled, routineDone[date.toEpochDay()].orEmpty(), zone, overrides)
                },
                timeline = timeline,
                overview = overview,
                overdueTasks = sections.overdue,
                dayTasks = sections.day,
                noDateTasks = sections.noDate,
                completedTasks = taskDoneToday
                    .filter { it !in struckInTrail }
                    .mapNotNull(byId::get)
                    .sortedBy { it.title.lowercase(AppLocale.LOCALE) }
                    .map { DayTask(it, done = true) },
                // O estado vazio olha TODAS as rotinas (inclusive pausadas: ter uma pausada não é "sem
                // rotinas") e qualquer tarefa (as sem data agora têm seção na Hoje).
                hasContent = routines.any { it.archivedAt == null } || tasks.isNotEmpty(),
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
        selection.value = if (date == lastDay?.today) Selection.FollowingToday else Selection.Chosen(date)
    }

    fun goToToday() {
        selection.value = Selection.FollowingToday
    }

    fun previousWeek() {
        lastDay?.let { select(it.selected.minusWeeks(1)) }
    }

    fun nextWeek() {
        lastDay?.let { select(it.selected.plusWeeks(1)) }
    }

    /** Marca como feito (com "Desfazer" no snackbar) ou, se já estava feito, desmarca. */
    fun toggle(block: DayTimeline.Block) {
        val (now, date) = lastDay ?: return
        // Pulado não se conclui: primeiro desfaz o pular.
        if (block.skipped || date.isAfter(now)) return
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
        val (now, date) = lastDay ?: return
        if (date.isAfter(now)) return
        viewModelScope.launch {
            if (done) {
                taskLogFor(task.id, date)?.let { taskRepository.undoCompletion(it) }
            } else {
                // Conclui NO DIA MOSTRADO (na hora atual do relógio); em hoje é o agora (decisão do Esquadro).
                // A data e a hora vêm do mesmo relógio do TodayRepository (sem relógio direto aqui).
                val clock = todayRepository.now()
                val completedAt = TaskSchedule.completionInstantOn(date, clock)
                taskCompletedEvents.send(taskRepository.logCompletion(task.id, completedAt, now = clock.toInstant().toEpochMilli()))
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

    /** As pendências de uma data, por seção. */
    private data class PendingSections(
        val overdue: List<DayTask> = emptyList(),
        val day: List<DayTask> = emptyList(),
        val noDate: List<DayTask> = emptyList(),
    )

    /**
     * Divide as pendentes do [overview] (fonte única, a do widget) nas seções da Hoje, na ordem dele:
     * atrasadas, de hoje SEM lembrete hoje e sem data. As com lembrete hoje ficam só na trilha. Uma
     * pendente cuja tarefa não está mais na lista (excluída no instante) é pulada.
     */
    private fun pendingSections(overview: TodayOverview, byId: Map<Long, Task>): PendingSections {
        fun List<TodayOverview.PendingTask>.rows() = mapNotNull { byId[it.taskId] }.map { DayTask(it, done = false) }
        return PendingSections(
            overdue = overview.pending.filter { it.overdue }.rows(),
            day = overview.pending.filter { it.dated && !it.overdue && it.reminderToday == null }.rows(),
            noDate = overview.pending.filter { !it.dated }.rows(),
        )
    }

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
                    app.todayRepository,
                )
            }
        }
    }
}
