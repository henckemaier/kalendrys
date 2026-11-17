package com.kalendrys.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.components.showUndo
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.tasks.NewTaskSheet
import com.kalendrys.app.ui.theme.Spacing
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest

/**
 * Tela Hoje (ADR-0002, 14.4; v1.8.0, E2): a CASCA, dividida do antigo `RoutinesScreen` sem regra nova
 * (o conteúdo novo é a E3). Faixa da semana e timeline em [TodayWeekStrip] e [TodayTimeline]; as
 * tarefas da trilha em `TodayTasks.kt`. O botão + cria TAREFA (P4), no molde do Calendário; "Nova
 * rotina" e "Todas as rotinas" ficam no menu da barra. Sem filtro por categoria (P3, foi para "Todas
 * as rotinas").
 */
@Composable
fun TodayScreen(
    onOpenRoutine: (Long) -> Unit,
    onNewRoutine: () -> Unit,
    onOpenAllRoutines: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenException: (routineId: Long, epochDay: Long) -> Unit,
    requestedDate: LocalDate? = null,
    onRequestedDateHandled: () -> Unit = {},
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)
    // Dia sugerido ao painel de nova tarefa; null = painel fechado.
    var newTaskEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(requestedDate) {
        if (requestedDate != null) {
            viewModel.select(requestedDate)
            onRequestedDateHandled()
        }
    }
    val completedMessage = stringResource(R.string.routine_completed)
    val taskCompletedMessage = stringResource(R.string.task_completed)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.completed.collectLatest { completed ->
            if (snackbarHostState.showUndo(completedMessage, undoLabel)) viewModel.undo(completed)
        }
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.taskCompleted.collectLatest { logId ->
            if (snackbarHostState.showUndo(taskCompletedMessage, undoLabel)) viewModel.undoTaskCompletion(logId)
        }
    }

    val todayLabel = stringResource(R.string.calendar_today)
    val settingsLabel = stringResource(R.string.settings_title)
    val allRoutinesLabel = stringResource(R.string.today_all_routines)
    val newRoutineLabel = stringResource(R.string.new_routine)
    AppScaffold(
        topBar = {
            // Barra pequena e fixa; a faixa da semana fica presa embaixo dela, com o mesmo fundo.
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                AppTopBar(
                    title = stringResource(R.string.tab_today),
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
                if (state.isLoaded && state.hasContent) {
                    TodayWeekStrip(
                        state = state,
                        onSelect = viewModel::select,
                        onPreviousWeek = viewModel::previousWeek,
                        onNextWeek = viewModel::nextWeek,
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.isLoaded) {
                CollapsingFab(
                    text = stringResource(R.string.new_task),
                    expanded = fabExpanded,
                    onClick = { newTaskEpochDay = state.selectedDate.toEpochDay() },
                    // O leitor de tela ouve em que dia a tarefa nasce, como no Calendário.
                    contentDescription = stringResource(R.string.new_task_on_day, weekdayDateLabel(state.selectedDate)),
                )
            }
        },
        hasFab = true,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            !state.hasContent -> EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = stringResource(R.string.routines_empty_title),
                message = stringResource(R.string.routines_empty_message),
                actionLabel = newRoutineLabel,
                onAction = onNewRoutine,
                modifier = Modifier.padding(padding),
            )
            else -> TodayTimeline(
                state = state,
                listState = listState,
                onOpenRoutine = onOpenRoutine,
                onOpenTask = onOpenTask,
                onOpenException = onOpenException,
                onToggle = viewModel::toggle,
                onToggleTask = viewModel::toggleTask,
                // Sem espaçamento entre itens: as linhas de ligação passam de um bloco para o outro.
                contentPadding = padding.plus(horizontal = Spacing.s, top = Spacing.xs),
            )
        }
    }

    newTaskEpochDay?.let { epochDay ->
        // Rascunho único (o mesmo de Tarefas e do Calendário): a data vira o dia selecionado, a não ser
        // que o usuário já a tenha escolhido no rascunho.
        LaunchedEffect(epochDay) { viewModel.openNewTask(LocalDate.ofEpochDay(epochDay)) }
        val lists by viewModel.lists.collectAsStateWithLifecycle()
        // Main.immediate: o texto digitado volta ao campo no mesmo quadro, sem saltar o cursor.
        val draft by viewModel.newTaskDraft.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
        draft?.let {
            NewTaskSheet(
                draft = it,
                onChange = viewModel::updateNewTask,
                onSave = {
                    viewModel.saveNewTask()
                    newTaskEpochDay = null
                },
                onCancel = {
                    viewModel.cancelNewTask()
                    newTaskEpochDay = null
                },
                onDismiss = { newTaskEpochDay = null },
                lists = lists,
            )
        }
    }
}
