@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.calendar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.domain.CalendarCalculator
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.calendar.CalendarViewModel.Entry
import com.kalendrys.app.ui.calendar.CalendarViewModel.RoutineEntry
import com.kalendrys.app.ui.calendar.CalendarViewModel.UiState
import com.kalendrys.app.ui.components.fabListContentPadding
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.HorizontalSwipeContent
import com.kalendrys.app.ui.components.SettingsButton
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.monthYearLabel
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.tasks.NewTaskSheet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun CalendarScreen(
    onOpenTask: (Long) -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    // Lembrete inicial do painel de nova tarefa; null = painel fechado.
    var newTaskEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            // Dias passados não aceitam lembrete, então o botão só aparece de hoje em diante.
            if (state.isLoaded) {
                ExtendedFloatingActionButton(
                    onClick = { newTaskEpochDay = state.selectedDate.toEpochDay() },
                    expanded = fabExpanded,
                    icon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(
                                R.string.new_task_on_day,
                                weekdayDateLabel(state.selectedDate),
                            ),
                        )
                    },
                    text = { Text(stringResource(R.string.new_task)) },
                )
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_calendar)) },
                actions = {
                    if (state.isLoaded && !state.isShowingToday) {
                        TextButton(onClick = viewModel::goToToday) {
                            Text(stringResource(R.string.calendar_today))
                        }
                    }
                    SettingsButton(onClick = onOpenSettings)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        if (state.isLoaded) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = fabListContentPadding(horizontal = 16.dp),
            ) {
                item(key = "month", contentType = "month") {
                    MonthCard(
                        state = state,
                        onPreviousMonth = { viewModel.showMonth(state.month.minusMonths(1)) },
                        onNextMonth = { viewModel.showMonth(state.month.plusMonths(1)) },
                        onSelectDate = viewModel::select,
                    )
                }
                dayAgenda(state, onOpenTask, onOpenRoutine)
            }
        }
    }

    newTaskEpochDay?.let { epochDay ->
        NewTaskSheet(
            initialDate = LocalDate.ofEpochDay(epochDay),
            onSave = { title, notes, timing, location ->
                viewModel.addTask(title, notes, timing, location)
                newTaskEpochDay = null
            },
            onDismiss = { newTaskEpochDay = null },
        )
    }
}

@Composable
private fun MonthCard(
    state: UiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { firstDayOfWeek(locale) }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = monthYearLabel(state.month, locale),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.calendar_previous_month),
                    )
                }
                IconButton(onClick = onNextMonth) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.calendar_next_month),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            WeekdayHeader(firstDayOfWeek, locale)
            HorizontalSwipeContent(
                target = state.month,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
                label = "month",
            ) { month ->
                MonthGrid(
                    month = month,
                    firstDayOfWeek = firstDayOfWeek,
                    state = state,
                    onSelectDate = onSelectDate,
                )
            }
            Spacer(Modifier.height(12.dp))
            Legend()
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(7) { offset ->
            Text(
                text = firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek,
    state: UiState,
    onSelectDate: (LocalDate) -> Unit,
) {
    val cells = remember(month, firstDayOfWeek) { CalendarCalculator.monthGrid(month, firstDayOfWeek) }
    Column {
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                isToday = date == state.today,
                                isSelected = date == state.selectedDate,
                                hasCompleted = state.hasCompleted(date),
                                hasReminders = state.remindersByDay.containsKey(date),
                                onClick = { onSelectDate(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasCompleted: Boolean,
    hasReminders: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val description = dayDescription(date, hasCompleted, hasReminders)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clearAndSetSemantics {}
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) colors.primary else Color.Transparent)
                .then(
                    if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier,
                ),
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
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .clearAndSetSemantics {}
                .height(5.dp),
        ) {
            if (hasCompleted) Dot(colors.primary)
            if (hasReminders) Dot(colors.tertiary)
        }
    }
}

@Composable
private fun Legend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, stringResource(R.string.calendar_completed))
        LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.calendar_reminders))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.dayAgenda(state: UiState, onOpenTask: (Long) -> Unit, onOpenRoutine: (Long) -> Unit) {
    item(key = "day_header", contentType = "day_header") {
        Text(
            text = weekdayDateLabel(state.selectedDate),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
    }
    val routines = state.routinesOnSelected
    val reminders = state.remindersOnSelected
    val completed = state.completedOnSelected
    if (routines.isEmpty() && reminders.isEmpty() && completed.isEmpty()) {
        item(key = "day_empty", contentType = "day_empty") {
            Text(
                text = stringResource(R.string.calendar_empty_day),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        return
    }
    routineSection(routines, onOpenRoutine)
    entrySection(
        key = "reminders",
        titleRes = R.string.calendar_reminders,
        entries = reminders,
        icon = Icons.Outlined.Notifications,
        iconTint = { MaterialTheme.colorScheme.tertiary },
        onOpenTask = onOpenTask,
    )
    entrySection(
        key = "completed",
        titleRes = R.string.calendar_completed,
        entries = completed,
        icon = Icons.Filled.CheckCircle,
        iconTint = { MaterialTheme.colorScheme.primary },
        onOpenTask = onOpenTask,
    )
}

private fun LazyListScope.entrySection(
    key: String,
    @StringRes titleRes: Int,
    entries: List<Entry>,
    icon: ImageVector,
    iconTint: @Composable () -> Color,
    onOpenTask: (Long) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "header_$key", contentType = "section_header") {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }
    itemsIndexed(
        entries,
        key = { index, entry -> "${key}_${entry.taskId}_${entry.epochMillis}_$index" },
        contentType = { _, _ -> "entry" },
    ) { index, entry ->
        EntryRow(
            entry = entry,
            icon = icon,
            iconTint = iconTint(),
            shape = groupedItemShape(index, entries.size),
            // Conclusões de tarefas excluídas ficam no histórico, mas não há tarefa para abrir.
            onClick = if (entry.title != null) ({ onOpenTask(entry.taskId) }) else null,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

private fun LazyListScope.routineSection(routines: List<RoutineEntry>, onOpenRoutine: (Long) -> Unit) {
    if (routines.isEmpty()) return
    item(key = "header_routines", contentType = "section_header") {
        Text(
            text = stringResource(R.string.calendar_routines),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
    }
    itemsIndexed(
        routines,
        key = { _, entry -> "routine_${entry.routine.id}" },
        contentType = { _, _ -> "routine" },
    ) { index, entry ->
        RoutineEntryRow(
            entry = entry,
            shape = groupedItemShape(index, routines.size),
            // Rotina excluída: o bloco fica no histórico do dia, mas não há mais o que editar.
            onClick = if (entry.routine.archivedAt == null) ({ onOpenRoutine(entry.routine.id) }) else null,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun RoutineEntryRow(
    entry: RoutineEntry,
    shape: Shape,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val rowModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = rowModifier) {
            RoutineEntryContent(entry)
        }
    } else {
        Surface(shape = shape, color = color, modifier = rowModifier) {
            RoutineEntryContent(entry)
        }
    }
}

@Composable
private fun RoutineEntryContent(entry: RoutineEntry) {
    val routine = entry.routine
    val tone = routineTone(RoutineColor.fromKey(routine.color))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(tone.fill),
        ) {
            Icon(
                imageVector = RoutineIcon.fromKey(routine.icon).imageVector,
                contentDescription = null,
                tint = tone.onFill,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = minuteOfDayLabel(entry.startMinute),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = routine.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (entry.done) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.done) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.calendar_completed),
                tint = tone.accent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: Entry,
    icon: ImageVector,
    iconTint: Color,
    shape: Shape,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val rowModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = rowModifier) {
            EntryContent(entry, icon, iconTint)
        }
    } else {
        Surface(shape = shape, color = color, modifier = rowModifier) {
            EntryContent(entry, icon, iconTint)
        }
    }
}

@Composable
private fun EntryContent(entry: Entry, icon: ImageVector, iconTint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = when {
                entry.done -> Icons.Filled.CheckCircle
                entry.recurring -> Icons.Outlined.Repeat
                else -> icon
            },
            contentDescription = if (entry.done) stringResource(R.string.calendar_completed) else null,
            tint = if (entry.done) MaterialTheme.colorScheme.primary else iconTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = entry.time?.format(TIME) ?: stringResource(R.string.calendar_all_day),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = entry.title ?: stringResource(R.string.calendar_deleted_task),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (entry.done) TextDecoration.LineThrough else null,
            color = if (entry.title == null || entry.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun dayDescription(date: LocalDate, hasCompleted: Boolean, hasReminders: Boolean): String {
    val parts = buildList {
        add(weekdayDateLabel(date))
        if (hasCompleted) add(stringResource(R.string.calendar_a11y_completed))
        if (hasReminders) add(stringResource(R.string.calendar_a11y_reminders))
    }
    return parts.joinToString(", ")
}
