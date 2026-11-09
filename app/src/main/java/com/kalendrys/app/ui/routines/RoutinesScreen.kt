@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.DayTimeline.Link
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.HorizontalSwipeContent
import com.kalendrys.app.ui.components.SettingsButton
import com.kalendrys.app.ui.components.rememberCurrentMinute
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.routines.RoutinesViewModel.DayTask
import com.kalendrys.app.ui.routines.RoutinesViewModel.UiState
import com.kalendrys.app.ui.routines.RoutinesViewModel.WeekDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

private val RowPadding = 6.dp
private val TimeColumnWidth = 44.dp
private val TrackWidth = 64.dp
private val PillWidth = 48.dp
private val PillShape = RoundedCornerShape(percent = 50)

@Composable
fun RoutinesScreen(
    onOpenRoutine: (Long) -> Unit,
    onNewRoutine: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    requestedDate: LocalDate? = null,
    onRequestedDateHandled: () -> Unit = {},
    viewModel: RoutinesViewModel = viewModel(factory = RoutinesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

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
            val result = snackbarHostState.showSnackbar(
                message = completedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(completed)
        }
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.taskCompleted.collectLatest { logId ->
            val result = snackbarHostState.showSnackbar(
                message = taskCompletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoTaskCompletion(logId)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // A faixa da semana fica presa embaixo da barra, com o mesmo fundo (sem emenda ao recolher).
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.tab_routines)) },
                    actions = {
                        if (state.isLoaded && !state.isShowingToday) {
                            TextButton(onClick = viewModel::goToToday) {
                                Text(stringResource(R.string.calendar_today))
                            }
                        }
                        SettingsButton(onClick = onOpenSettings)
                    },
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                if (state.isLoaded && state.hasContent) {
                    WeekStrip(
                        state = state,
                        onSelect = viewModel::select,
                        onPreviousWeek = viewModel::previousWeek,
                        onNextWeek = viewModel::nextWeek,
                    )
                }
                if (state.isLoaded && state.categories.isNotEmpty()) {
                    CategoryFilterRow(
                        categories = state.categories,
                        selectedId = state.selectedCategoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRoutine,
                expanded = fabExpanded,
                // O ExtendedFAB remove o texto da semântica; quem dá o rótulo acessível é o ícone.
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_routine)) },
                text = { Text(stringResource(R.string.new_routine)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            !state.isLoaded -> Unit
            !state.hasContent -> EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = stringResource(R.string.routines_empty_title),
                message = stringResource(R.string.routines_empty_message),
                modifier = Modifier.padding(innerPadding),
            )
            else -> Timeline(
                state = state,
                listState = listState,
                onOpenRoutine = onOpenRoutine,
                onOpenTask = onOpenTask,
                onToggle = viewModel::toggle,
                onToggleTask = viewModel::toggleTask,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun CategoryFilterRow(
    categories: List<RoutineCategory>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        item(key = "category_all") {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.routine_category_filter_all)) },
            )
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
                leadingIcon = {
                    Dot(color = routineTone(RoutineColor.fromKey(category.color)).accent, size = 10.dp, filled = true)
                },
            )
        }
    }
}

@Composable
private fun WeekStrip(
    state: UiState,
    onSelect: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val locale = Locale.getDefault()
    val byDate = remember(state.week) { state.week.associateBy { it.date } }
    val previousLabel = stringResource(R.string.routine_week_previous)
    val nextLabel = stringResource(R.string.routine_week_next)
    HorizontalSwipeContent(
        target = state.week.first().date,
        onPrevious = onPreviousWeek,
        onNext = onNextWeek,
        label = "week",
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) { onPreviousWeek(); true },
                    CustomAccessibilityAction(nextLabel) { onNextWeek(); true },
                )
            },
    ) { weekStart ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            for (offset in 0L until 7L) {
                val date = weekStart.plusDays(offset)
                WeekDayCell(
                    date = date,
                    // Durante a animação a semana que sai não tem mais dados: aparece sem pontos.
                    day = byDate[date],
                    isToday = date == state.today,
                    isSelected = date == state.selectedDate,
                    locale = locale,
                    onClick = { onSelect(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WeekDayCell(
    date: LocalDate,
    day: WeekDay?,
    isToday: Boolean,
    isSelected: Boolean,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val description = if (day != null && day.total > 0) {
        stringResource(R.string.routine_day_a11y, weekdayDateLabel(date), day.done, day.total)
    } else {
        weekdayDateLabel(date)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.height(4.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clearAndSetSemantics {}
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) colors.primary else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier),
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSelected -> colors.onPrimary
                    isToday -> colors.primary
                    else -> colors.onSurface
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .clearAndSetSemantics {}
                .height(6.dp),
        ) {
            day?.dots?.forEach { dot ->
                Dot(color = routineTone(dot.color).accent, size = 6.dp, filled = dot.done)
            }
        }
    }
}

@Composable
private fun Timeline(
    state: UiState,
    listState: LazyListState,
    onOpenRoutine: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onToggle: (DayTimeline.Block) -> Unit,
    onToggleTask: (Task, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberCurrentMinute()
    // Muda a cada minuto; cada linha recebe só um Boolean e só recompõe quando o bloco entra ou sai de andamento.
    val nowMinute = remember(now) { LocalTime.now().let { it.hour * 60 + it.minute } }
    var tasksExpanded by rememberSaveable { mutableStateOf(true) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Sem espaçamento entre itens: as linhas de ligação passam de um bloco para o outro.
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 104.dp),
    ) {
        if (state.untimedTasks.isNotEmpty()) {
            item(key = "today_tasks_header", contentType = "tasks_header") {
                DayTasksHeader(
                    title = if (state.isShowingToday) {
                        stringResource(R.string.routines_day_tasks_today)
                    } else {
                        stringResource(R.string.routines_day_tasks)
                    },
                    count = state.untimedTasks.size,
                    expanded = tasksExpanded,
                    onToggle = { tasksExpanded = !tasksExpanded },
                )
            }
            if (tasksExpanded) {
                items(
                    items = state.untimedTasks,
                    key = { "band_${it.task.id}" },
                    contentType = { "band_task" },
                ) { dayTask ->
                    DayTaskRow(
                        dayTask = dayTask,
                        canComplete = state.canComplete,
                        onOpen = { onOpenTask(dayTask.task.id) },
                        onToggle = { onToggleTask(dayTask.task, dayTask.done) },
                    )
                }
            }
        }
        if (state.timeline.isEmpty() && state.untimedTasks.isEmpty()) {
            item(key = "empty_day", contentType = "empty_day") {
                Text(
                    text = stringResource(R.string.routines_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
        items(
            items = state.timeline,
            key = { it.key },
            contentType = {
                when (it) {
                    is DayTimeline.Block -> "block"
                    is DayTimeline.Gap -> "gap"
                    is DayTimeline.TaskItem -> "task"
                }
            },
        ) { item ->
            when (item) {
                is DayTimeline.Block -> BlockRow(
                    block = item,
                    inProgress = state.isShowingToday && nowMinute >= item.startMinute && nowMinute < item.endMinute,
                    canComplete = state.canComplete,
                    // Rotina excluída: o bloco fica nos dias passados, mas não há mais o que editar.
                    onOpen = if (item.routine.archivedAt == null) ({ onOpenRoutine(item.routine.id) }) else null,
                    onToggle = { onToggle(item) },
                )
                is DayTimeline.Gap -> GapRow(item)
                is DayTimeline.TaskItem -> TaskRow(
                    item = item,
                    canComplete = state.canComplete,
                    onOpen = { onOpenTask(item.task.id) },
                    onToggle = { onToggleTask(item.task, item.done) },
                )
            }
        }
    }
}

/** A pílula cresce com a duração: 30 min ou menos é um círculo, a partir de ~1h40 para de crescer. */
private fun pillHeight(durationMinutes: Int): Dp = (durationMinutes * 1.6f).coerceIn(48f, 160f).dp

@Composable
private fun BlockRow(
    block: DayTimeline.Block,
    inProgress: Boolean,
    canComplete: Boolean,
    onOpen: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val routine = block.routine
    val tone = routineTone(RoutineColor.fromKey(routine.color))
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val pillHeight = pillHeight(routine.durationMinutes)
    val hasDuration = routine.durationMinutes > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Column(
            verticalArrangement = if (hasDuration) Arrangement.SpaceBetween else Arrangement.Center,
            modifier = Modifier
                .width(TimeColumnWidth)
                .fillMaxHeight()
                .padding(vertical = RowPadding + 2.dp)
                .clearAndSetSemantics {},
        ) {
            Text(
                text = minuteOfDayLabel(routine.startMinute),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasDuration) {
                Text(
                    text = minuteOfDayLabel(routine.endMinute),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                .drawBehind { drawLinks(block.linkAbove, block.linkBelow, pillHeight.toPx(), lineColor) },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = RowPadding)
                    .size(width = PillWidth, height = pillHeight)
                    .clip(PillShape)
                    .background(tone.fill)
                    .then(
                        if (inProgress) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, PillShape) else Modifier,
                    ),
            ) {
                Icon(
                    imageVector = RoutineIcon.fromKey(routine.icon).imageVector,
                    contentDescription = null,
                    tint = tone.onFill,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .padding(start = 8.dp, top = RowPadding, bottom = RowPadding),
        ) {
            if (inProgress) {
                Text(
                    text = stringResource(R.string.routine_now),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = routineTimeRange(routine),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (routine.remind) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.routine_reminds),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                    )
                }
            }
            Text(
                text = routine.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (block.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (block.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onToggle,
            enabled = canComplete,
            modifier = Modifier.align(Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = if (block.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = stringResource(
                    if (block.done) R.string.routine_uncomplete else R.string.routine_complete,
                    routine.title,
                ),
                tint = if (canComplete) tone.accent else tone.accent.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun GapRow(gap: DayTimeline.Gap) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Spacer(Modifier.width(TimeColumnWidth))
        Box(
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight()
                .drawBehind { drawLink(Link.DASHED, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), lineColor) },
        )
        Icon(
            imageVector = Icons.Outlined.LocalCafe,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp),
        )
        Text(
            text = stringResource(R.string.routine_free_time, DurationFormat.format(gap.minutes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Uma tarefa pontual na trilha. Destaque OPOSTO ao do bloco de rotina (pílula sólida + círculo):
 * cartão de borda tracejada + marcador losango, para ler como um evento único que não se repete.
 */
@Composable
private fun TaskRow(
    item: DayTimeline.TaskItem,
    canComplete: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    val task = item.task
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TimeColumnWidth)
                .padding(vertical = RowPadding + 2.dp)
                .clearAndSetSemantics {},
        ) {
            Text(
                text = minuteOfDayLabel(item.startMinute),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(TrackWidth)
                .fillMaxHeight(),
        ) {
            Diamond(filled = item.done, color = accent)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = RowPadding)
                .dashedBorder(accent.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TaskCheck(done = item.done, title = task.title, accent = accent, canComplete = canComplete, onToggle = onToggle)
    }
}

@Composable
private fun DayTasksHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.routines_day_tasks_collapse else R.string.routines_day_tasks_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayTaskRow(
    dayTask: DayTask,
    canComplete: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    val task = dayTask.task
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Diamond(filled = dayTask.done, color = accent)
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dayTask.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (dayTask.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        TaskCheck(done = dayTask.done, title = task.title, accent = accent, canComplete = canComplete, onToggle = onToggle)
    }
}

@Composable
private fun TaskCheck(
    done: Boolean,
    title: String,
    accent: Color,
    canComplete: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(
        onClick = onToggle,
        enabled = canComplete,
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = stringResource(
                if (done) R.string.task_uncomplete else R.string.task_complete,
                title,
            ),
            tint = if (canComplete) accent else accent.copy(alpha = 0.38f),
        )
    }
}

/** Marcador em losango (quadrado girado 45°): cheio quando concluído, só contorno quando pendente. */
@Composable
private fun Diamond(filled: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .rotate(45f)
            .then(
                if (filled) {
                    Modifier.background(color, RoundedCornerShape(3.dp))
                } else {
                    Modifier.border(2.dp, color, RoundedCornerShape(3.dp))
                },
            ),
    )
}

/** Borda tracejada arredondada, o oposto visual da pílula sólida da rotina. */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val radius = 12.dp.toPx()
    val dash = 4.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}

/** Linhas acima e abaixo da pílula, que fica centralizada no trilho. */
private fun DrawScope.drawLinks(above: Link, below: Link, pillHeightPx: Float, color: Color) {
    val x = size.width / 2
    val pillTop = (size.height - pillHeightPx) / 2
    drawLink(above, Offset(x, 0f), Offset(x, pillTop), color)
    drawLink(below, Offset(x, pillTop + pillHeightPx), Offset(x, size.height), color)
}

private fun DrawScope.drawLink(link: Link, start: Offset, end: Offset, color: Color) {
    if (link == Link.NONE || end.y <= start.y) return
    val dash = 4.dp.toPx()
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = 2.dp.toPx(),
        pathEffect = if (link == Link.DASHED) PathEffect.dashPathEffect(floatArrayOf(dash, dash)) else null,
    )
}
