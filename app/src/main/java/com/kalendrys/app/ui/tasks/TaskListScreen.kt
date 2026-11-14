@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.ui.components.fabListContentPadding
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.SettingsButton
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.tasks.TaskViewModel.UndoableAction
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest

@Composable
fun TaskListScreen(
    onOpenTask: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    openNewTask: Boolean = false,
    onNewTaskOpened: () -> Unit = {},
    viewModel: TaskViewModel = viewModel(factory = TaskViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    var showNewTask by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openNewTask) {
        if (openNewTask) {
            showNewTask = true
            onNewTaskOpened()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val completedMessage = stringResource(R.string.task_completed)
    val deletedMessage = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.undoable.collectLatest { action ->
            val (message, duration) = when (action) {
                is UndoableAction.Completed -> completedMessage to SnackbarDuration.Short
                is UndoableAction.Deleted -> deletedMessage to SnackbarDuration.Long
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(action)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_tasks)) },
                actions = { SettingsButton(onClick = onOpenSettings) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewTask = true },
                expanded = fabExpanded,
                // O ExtendedFAB remove o texto da semântica; quem dá o rótulo acessível é o ícone.
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_task)) },
                text = { Text(stringResource(R.string.new_task)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            !state.isLoaded -> Unit
            // Sem nenhuma tarefa criada: vazio "comece por aqui" (sem busca nem filtros).
            !state.hasTasks -> EmptyState(
                icon = Icons.Outlined.TaskAlt,
                title = stringResource(R.string.empty_tasks_title),
                message = stringResource(R.string.empty_tasks_message),
                modifier = Modifier.padding(innerPadding),
            )
            else -> Column(modifier = Modifier.padding(innerPadding)) {
                TaskSearchBar(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                TaskFilterChips(
                    selected = filters,
                    onToggle = viewModel::toggleFilter,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.isEmpty) {
                    // Há tarefas, mas a busca/filtros não deixaram nenhuma: vazio distinto.
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.empty_search_title),
                        message = stringResource(R.string.empty_search_message),
                    )
                } else {
                    val today = state.today
                    val todayLabel = remember(today) { weekdayDateLabel(today) }
                    val sections = listOf(
                        Triple("overdue", R.string.section_overdue, state.overdue),
                        Triple("today", R.string.section_today, state.dueToday),
                        Triple("upcoming", R.string.section_upcoming, state.upcoming),
                        Triple("no_date", R.string.section_no_date, state.noDate),
                        Triple("done", R.string.section_done_today, state.doneToday),
                    )
                    val titles = sections.associate { (key, titleRes, _) -> key to stringResource(titleRes) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = fabListContentPadding(horizontal = 16.dp),
                    ) {
                        item(key = "today_label", contentType = "today") {
                            Text(
                                text = todayLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        sections.forEach { (key, _, items) ->
                            taskSection(
                                key = key,
                                title = titles.getValue(key),
                                items = items,
                                done = key == "done",
                                overdue = key == "overdue",
                                // Na seção "Hoje" a data é óbvia; nas outras ela aparece na linha.
                                showDate = key == "overdue" || key == "upcoming",
                                today = today,
                                onOpen = { onOpenTask(it.id) },
                                onComplete = viewModel::complete,
                                onDelete = viewModel::delete,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewTask) {
        NewTaskSheet(
            onSave = { title, notes, timing, location ->
                viewModel.addTask(title, notes, timing, location)
                showNewTask = false
            },
            onDismiss = { showNewTask = false },
        )
    }
}

/** Campo de busca filled, no estilo dos cards da lista; o X aparece quando há texto. */
@Composable
private fun TaskSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.task_search_hint)) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.task_search_clear))
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Chips de seção (seleção múltipla). Nenhum ligado = mostra tudo; ligados = só aquelas seções. */
@Composable
private fun TaskFilterChips(
    selected: Set<TaskViewModel.Filter>,
    onToggle: (TaskViewModel.Filter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(
        TaskViewModel.Filter.OVERDUE to R.string.section_overdue,
        TaskViewModel.Filter.TODAY to R.string.section_today,
        TaskViewModel.Filter.UPCOMING to R.string.section_upcoming,
        TaskViewModel.Filter.NO_DATE to R.string.section_no_date,
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { (filter, label) ->
            val isSelected = filter in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(filter) },
                label = { Text(stringResource(label)) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

private fun LazyListScope.taskSection(
    key: String,
    title: String,
    items: List<TaskViewModel.Item>,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    onOpen: (Task) -> Unit,
    onComplete: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header_$key", contentType = "header") {
        SectionHeader(title = title, count = items.size, modifier = Modifier.animateItem())
    }
    itemsIndexed(items, key = { _, item -> item.task.id }, contentType = { _, _ -> "task" }) { index, item ->
        SwipeableTaskRow(
            item = item,
            done = done,
            overdue = overdue,
            showDate = showDate,
            today = today,
            shape = groupedItemShape(index, items.size),
            onOpen = { onOpen(item.task) },
            onComplete = { onComplete(item.task) },
            onDelete = { onDelete(item.task) },
            modifier = Modifier
                .animateItem()
                .padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwipeableTaskRow(
    item: TaskViewModel.Item,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    shape: Shape,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnDelete by rememberUpdatedState(onDelete)
    // remember (e não rememberSaveable): a LazyColumn restauraria o estado "dispensado"
    // quando o "Desfazer" devolve um item com a mesma key.
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            positionalThreshold = { totalDistance -> totalDistance * 0.4f },
        )
    }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete()
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val swiping = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(if (swiping) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        TaskRow(
            item = item,
            done = done,
            overdue = overdue,
            showDate = showDate,
            today = today,
            shape = shape,
            onOpen = onOpen,
            onComplete = onComplete,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun TaskRow(
    item: TaskViewModel.Item,
    done: Boolean,
    overdue: Boolean,
    showDate: Boolean,
    today: LocalDate,
    shape: Shape,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = item.task
    val deleteLabel = stringResource(R.string.delete_task)
    Surface(
        onClick = onOpen,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
            },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Uma que se repete e não acontece hoje (em "Próximas") não tem o que concluir agora.
            val canComplete = !done && !(task.repeat != ReminderRepeat.NONE && item.date != null && item.date != today)
            IconButton(onClick = onComplete, enabled = canComplete) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = stringResource(if (done) R.string.done_today else R.string.complete_task),
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.notes.isNotBlank()) {
                    Text(
                        text = task.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TaskMeta(
                    task = task,
                    date = item.date.takeIf { showDate },
                    reminderTime = item.reminderTime,
                    overdue = overdue,
                    today = today,
                )
            }
        }
    }
}

/** Linha de detalhes: data (fora da seção "Hoje"), horário do lembrete, repetição e local. */
@Composable
private fun TaskMeta(
    task: Task,
    date: LocalDate?,
    reminderTime: LocalTime?,
    overdue: Boolean,
    today: LocalDate,
) {
    // Data em que a repetição se apoia (só existe se a tarefa se repete).
    val repeatFrom = TaskSchedule.dueDate(task)?.takeIf { task.repeat != ReminderRepeat.NONE }
    if (date == null && reminderTime == null && repeatFrom == null && task.location.isBlank()) return
    val accent = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (date != null) {
            MetaItem(Icons.Outlined.Event, dateLabel(date, today), accent)
        }
        if (reminderTime != null) {
            MetaItem(Icons.Outlined.Notifications, reminderTime.format(TIME), accent)
        }
        if (repeatFrom != null) {
            MetaItem(Icons.Outlined.Repeat, repeatLabel(task.repeat, repeatFrom), muted)
        }
        if (task.location.isNotBlank()) {
            MetaItem(Icons.Outlined.Place, task.location, muted)
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
