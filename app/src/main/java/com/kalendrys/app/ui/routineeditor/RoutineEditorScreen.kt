@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.routineeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.ui.components.firstDayOfWeek
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.routineeditor.RoutineEditorViewModel.Event
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RoutineEditorScreen(
    onBack: () -> Unit,
    viewModel: RoutineEditorViewModel = viewModel(factory = RoutineEditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                Event.Closed -> currentOnBack()
            }
        }
    }

    fun requestBack() {
        if (state.hasChanges) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = state.hasChanges) { showDiscardDialog = true }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.routine_new_title else R.string.routine_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.draft != null) {
                        if (!state.isNew) {
                            val paused = state.routine?.pausedAt != null
                            IconButton(onClick = viewModel::togglePause) {
                                Icon(
                                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                    contentDescription = stringResource(
                                        if (paused) R.string.routine_resume else R.string.routine_pause,
                                    ),
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.routine_delete))
                            }
                        }
                        TextButton(onClick = viewModel::save, enabled = state.canSave) {
                            Text(stringResource(R.string.save))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val draft = state.draft
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.routine_not_found_title),
                message = stringResource(R.string.routine_not_found_message),
                modifier = Modifier.padding(innerPadding),
            )
            draft == null -> Unit
            else -> EditorContent(
                draft = draft,
                viewModel = viewModel,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.routine_delete_confirm_title)) },
            text = { Text(stringResource(R.string.routine_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.routine_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun EditorContent(
    draft: RoutineDraft,
    viewModel: RoutineEditorViewModel,
    modifier: Modifier = Modifier,
) {
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item(key = "preview") { Preview(draft) }
        item(key = "title") {
            OutlinedTextField(
                value = draft.title,
                onValueChange = viewModel::updateTitle,
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
        item(key = "icon") {
            SectionTitle(stringResource(R.string.routine_icon))
            IconPicker(selected = draft.icon, color = draft.color, onSelect = viewModel::updateIcon)
        }
        item(key = "color") {
            SectionTitle(stringResource(R.string.routine_color))
            ColorPicker(selected = draft.color, onSelect = viewModel::updateColor)
        }
        item(key = "time") {
            SectionTitle(stringResource(R.string.routine_duration))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutineDraft.DURATION_PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = draft.durationMinutes == minutes,
                        onClick = { viewModel.updateDuration(minutes) },
                        label = {
                            Text(if (minutes == 0) stringResource(R.string.routine_no_duration) else DurationFormat.format(minutes))
                        },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            TimeRow(
                label = stringResource(R.string.routine_start),
                value = minuteOfDayLabel(draft.startMinute),
                index = 0,
                onClick = { showStartPicker = true },
            )
            TimeRow(
                label = stringResource(R.string.routine_end),
                value = when {
                    draft.durationMinutes == 0 -> "—"
                    draft.endsNextDay -> stringResource(R.string.routine_end_next_day, minuteOfDayLabel(draft.endMinuteOfDay))
                    else -> minuteOfDayLabel(draft.endMinuteOfDay)
                },
                index = 1,
                onClick = { showEndPicker = true },
            )
        }
        item(key = "days") {
            SectionTitle(stringResource(R.string.routine_days))
            DayPicker(mask = draft.daysOfWeek, onToggle = viewModel::toggleDay)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                listOf(
                    RoutineDays.ALL to R.string.routine_days_everyday,
                    RoutineDays.WEEKDAYS to R.string.routine_days_weekdays,
                    RoutineDays.WEEKEND to R.string.routine_days_weekend,
                ).forEach { (mask, label) ->
                    FilterChip(
                        selected = draft.daysOfWeek == mask,
                        onClick = { viewModel.setDays(mask) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            if (!draft.hasDays) {
                Text(
                    text = stringResource(R.string.routine_days_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        item(key = "recurrence") {
            SectionTitle(stringResource(R.string.routine_repeat))
            WeekIntervalStepper(
                weeks = draft.weekInterval,
                onChange = viewModel::updateWeekInterval,
            )
            SectionTitle(stringResource(R.string.routine_validity))
            DateRow(
                label = stringResource(R.string.routine_start_date),
                value = draft.startEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_any),
                index = 0,
                onClick = { showStartDatePicker = true },
                onClear = draft.startEpochDay?.let { { viewModel.updateStartDate(null) } },
            )
            DateRow(
                label = stringResource(R.string.routine_end_date),
                value = draft.endEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_none),
                index = 1,
                onClick = { showEndDatePicker = true },
                onClear = draft.endEpochDay?.let { { viewModel.updateEndDate(null) } },
            )
            if (!draft.datesValid) {
                Text(
                    text = stringResource(R.string.routine_dates_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        item(key = "remind") {
            RemindRow(
                checked = draft.remind,
                onCheckedChange = viewModel::updateRemind,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        item(key = "notes") {
            OutlinedTextField(
                value = draft.notes,
                onValueChange = viewModel::updateNotes,
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
    }

    if (showStartPicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_pick_start),
            initialTime = timeOf(draft.startMinute),
            onConfirm = {
                viewModel.updateStart(it)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            title = stringResource(R.string.routine_pick_end),
            // Sem duração ainda: sugere meia hora depois do início.
            initialTime = timeOf(
                if (draft.durationMinutes == 0) draft.startMinute + RoutineDraft.DEFAULT_DURATION_MINUTES else draft.endMinuteOfDay,
            ),
            onConfirm = {
                viewModel.updateEnd(it)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
    if (showStartDatePicker) {
        RoutineDatePicker(
            initialEpochDay = draft.startEpochDay,
            onConfirm = {
                viewModel.updateStartDate(it)
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        RoutineDatePicker(
            initialEpochDay = draft.endEpochDay ?: draft.startEpochDay,
            onConfirm = {
                viewModel.updateEndDate(it)
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
}

private fun timeOf(minute: Int): LocalTime = LocalTime.of(minute % 1440 / 60, minute % 60)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** Como o bloco vai aparecer na linha do tempo. */
@Composable
private fun Preview(draft: RoutineDraft) {
    val tone = routineTone(draft.color)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(tone.fill),
        ) {
            Icon(draft.icon.imageVector, contentDescription = null, tint = tone.onFill)
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = when (draft.durationMinutes) {
                    0 -> minuteOfDayLabel(draft.startMinute)
                    else -> stringResource(
                        R.string.routine_time_range,
                        minuteOfDayLabel(draft.startMinute),
                        minuteOfDayLabel(draft.endMinuteOfDay),
                        DurationFormat.format(draft.durationMinutes),
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = draft.title.ifBlank { stringResource(R.string.routine_new_title) },
                style = MaterialTheme.typography.titleMedium,
                color = if (draft.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconPicker(selected: RoutineIcon, color: RoutineColor, onSelect: (RoutineIcon) -> Unit) {
    val tone = routineTone(color)
    val labels = stringArrayResource(R.array.routine_icon_labels)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoutineIcon.entries.forEach { icon ->
            val isSelected = icon == selected
            val description = stringResource(R.string.routine_icon_option, labels.getOrElse(icon.ordinal) { icon.name })
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) tone.fill else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(icon) })
                    .semantics { contentDescription = description },
            ) {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = null,
                    tint = if (isSelected) tone.onFill else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorPicker(selected: RoutineColor, onSelect: (RoutineColor) -> Unit) {
    val labels = stringArrayResource(R.array.routine_color_labels)
    // As 8 cores cabem numa linha só, distribuídas na largura.
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        RoutineColor.entries.forEach { color ->
            val tone = routineTone(color)
            val isSelected = color == selected
            val description = stringResource(R.string.routine_color_option, labels.getOrElse(color.ordinal) { color.name })
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tone.fill)
                    .then(if (isSelected) Modifier.border(2.dp, tone.onFill, CircleShape) else Modifier)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics { contentDescription = description },
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = tone.onFill, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, value: String, index: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = groupedItemShape(index, 2),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeekIntervalStepper(weeks: Int, onChange: (Int) -> Unit) {
    val label = if (weeks <= 1) {
        stringResource(R.string.routine_week_interval_weekly)
    } else {
        stringResource(R.string.routine_week_interval_every, weeks)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label },
            )
            IconButton(
                onClick = { onChange(weeks - 1) },
                enabled = weeks > 1,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.routine_week_decrease))
            }
            IconButton(
                onClick = { onChange(weeks + 1) },
                enabled = weeks < RoutineDraft.MAX_WEEK_INTERVAL,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.routine_week_increase))
            }
        }
    }
}

@Composable
private fun DateRow(label: String, value: String, index: Int, onClick: () -> Unit, onClear: (() -> Unit)?) {
    Surface(
        onClick = onClick,
        shape = groupedItemShape(index, 2),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.routine_date_clear))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun RoutineDatePicker(initialEpochDay: Long?, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initialEpochDay ?: LocalDate.now().toEpochDay()) * MILLIS_PER_DAY,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(it / MILLIS_PER_DAY) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)

@Composable
private fun DayPicker(mask: Int, onToggle: (DayOfWeek) -> Unit) {
    val locale = Locale.getDefault()
    val firstDay = firstDayOfWeek(locale)
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        for (offset in 0L until 7L) {
            val day = firstDay.plus(offset)
            val selected = RoutineDays.contains(mask, day)
            val colors = MaterialTheme.colorScheme
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
            ) {
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun RemindRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.routine_remind), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.routine_remind_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            // O toque vale para a linha inteira (toggleable acima); o Switch só mostra o estado.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
