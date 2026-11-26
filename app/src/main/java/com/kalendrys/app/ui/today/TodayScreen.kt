package com.kalendrys.app.ui.today

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.LocalAppClock
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.WeekdayName
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.rememberCurrentMinute
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.components.weekdayName
import com.kalendrys.app.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate

/**
 * Tela Hoje (ADR-0002, 14.4; v1.8.0, E2 e E3). A tela inteira é UMA lista: a faixa da semana rola
 * junto com o conteúdo (resolve a paisagem pela raiz). Na data de hoje: progresso do dia, próximo
 * item, a trilha, Atrasadas, Hoje, Sem data (recolhida) e Concluídas (recolhida); em outro dia, a
 * trilha e as tarefas daquele dia. Progresso, próximo item e pendências vêm do mesmo retrato do
 * widget Resumo ([com.kalendrys.app.domain.TodayOverview]).
 *
 * O botão + cria TAREFA (P4), no molde do Calendário; "Nova rotina" e "Todas as rotinas" ficam no
 * menu da barra. O título é "Hoje" na data de hoje e o dia mostrado nos outros (com o botão Hoje).
 * Sem deslize de linha (4.2): o círculo conclui e o segurar do bloco abre Pular/Remarcar este dia.
 */
@Composable
fun TodayScreen(
    onOpenRoutine: (Long) -> Unit,
    onNewRoutine: () -> Unit,
    onOpenAllRoutines: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenException: (routineId: Long, epochDay: Long) -> Unit,
    /** Pede o painel de nova tarefa (hospedado no topo da navegação, E6) sugerindo o dia mostrado. */
    onNewTask: (LocalDate) -> Unit = {},
    requestedDate: LocalDate? = null,
    onRequestedDateHandled: () -> Unit = {},
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)
    // Seções recolhíveis: fechadas por padrão; aberta/fechada é estado de tela.
    var noDateExpanded by rememberSaveable { mutableStateOf(false) }
    var doneExpanded by rememberSaveable { mutableStateOf(false) }

    // Relógio ou fuso trocado com a Hoje por trás: ao voltar, a data é relida (O-4).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    LaunchedEffect(requestedDate) {
        if (requestedDate != null) {
            viewModel.select(requestedDate)
            onRequestedDateHandled()
        }
    }
    // Concluir e pular pela Hoje oferecem Desfazer pela oferta do app (v1.9.0, E4, 15.6): a mensagem é
    // a do KalendrysNavHost (UndoOfferEffect) e sobrevive a girar e a trocar de aba. Sem mensagem própria.

    val todayLabel = stringResource(R.string.calendar_today)
    val settingsLabel = stringResource(R.string.settings_title)
    val allRoutinesLabel = stringResource(R.string.today_all_routines)
    val newRoutineLabel = stringResource(R.string.new_routine)
    val newTaskLabel = stringResource(R.string.new_task)
    val openNewTask = { onNewTask(state.selectedDate) }
    AppScaffold(
        topBar = {
            AppTopBar(
                title = if (state.isShowingToday) stringResource(R.string.tab_today) else shortDayLabel(state.selectedDate),
                actions = buildList {
                    if (state.isLoaded && !state.isShowingToday) {
                        add(TopBarAction(todayLabel, prominent = true, onClick = viewModel::goToToday))
                    }
                    add(TopBarAction(settingsLabel, Icons.Outlined.Settings, onClick = onOpenSettings))
                },
                menu = listOf(
                    TopBarAction(allRoutinesLabel, onClick = onOpenAllRoutines),
                    TopBarAction(newRoutineLabel, onClick = onNewRoutine),
                ),
            )
        },
        floatingActionButton = {
            if (state.isLoaded) {
                CollapsingFab(
                    text = newTaskLabel,
                    expanded = fabExpanded,
                    onClick = openNewTask,
                    // O leitor de tela ouve em que dia a tarefa nasce, como no Calendário.
                    contentDescription = stringResource(R.string.new_task_on_day, weekdayDateLabel(state.selectedDate)),
                )
            }
        },
        hasFab = true,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            // Primeira tela de quem instala: fala do dia e oferece os dois começos.
            !state.hasContent -> EmptyState(
                icon = Icons.Outlined.WbSunny,
                title = stringResource(R.string.today_empty_title),
                message = stringResource(R.string.today_empty_message),
                actionLabel = newTaskLabel,
                onAction = openNewTask,
                secondaryActionLabel = newRoutineLabel,
                onSecondaryAction = onNewRoutine,
                contentPadding = padding,
            )
            else -> {
                val now = rememberCurrentMinute()
                // Muda a cada minuto; cada linha recebe só um Boolean e só recompõe quando o bloco entra ou sai de andamento.
                val zone = LocalAppClock.current.zone()
                val nowMinute = remember(now, zone) { Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute } }
                val trailActions = TrailActions(
                    onOpenRoutine = onOpenRoutine,
                    onOpenTask = onOpenTask,
                    onReschedule = onOpenException,
                    onToggle = viewModel::toggle,
                    onToggleTask = viewModel::toggleTask,
                    // No dia da OCORRÊNCIA (o de origem, se o bloco veio remarcado de outro dia), não no mostrado.
                    onSkip = { block -> viewModel.skip(block.routine.id, block.occurrenceDate) },
                    onUnskip = { block -> viewModel.unskip(block.routine.id, block.occurrenceDate) },
                )
                val sections = SectionsState(
                    noDateExpanded = noDateExpanded,
                    doneExpanded = doneExpanded,
                    onToggleNoDate = { noDateExpanded = !noDateExpanded },
                    onToggleDone = { doneExpanded = !doneExpanded },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Sem espaçamento entre itens: as linhas de ligação passam de um bloco para o outro.
                    contentPadding = padding.plus(horizontal = Spacing.s, top = Spacing.xs),
                ) {
                    item(key = "week", contentType = "week") {
                        TodayWeekStrip(
                            state = state,
                            onSelect = viewModel::select,
                            onPreviousWeek = viewModel::previousWeek,
                            onNextWeek = viewModel::nextWeek,
                        )
                    }
                    val overview = state.overview
                    if (overview != null) {
                        item(key = "progress", contentType = "progress") {
                            TodayProgress(overview, done = state.doneToday ?: 0, pending = state.pendingToday ?: 0)
                        }
                        item(key = "next", contentType = "next") { TodayNextUp(overview) }
                    }
                    todayTrail(state, nowMinute, trailActions)
                    todaySections(state, sections, onOpenTask, viewModel::toggleTask)
                }
            }
        }
    }

}

/** "Qua, 18 nov": título da barra fora da data de hoje (mesmas tabelas pt-BR dos widgets). */
@Composable
private fun shortDayLabel(date: LocalDate): String {
    val months = stringArrayResource(R.array.month_short)
    return "${weekdayName(date.dayOfWeek, WeekdayName.SHORT)}, ${date.dayOfMonth} ${months[date.monthValue - 1]}"
}
