@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.kalendrys.app.R
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.components.TimePickerDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Data, repetição e lembrete da tarefa, em chips: a data abre o calendário, o lembrete abre o
 * relógio (um horário nesse dia) e a repetição vale a partir da data. Sem data não há repetição
 * nem lembrete. [warnIfPast] = false trata um lembrete único que já passou como informação.
 */
@Composable
fun TaskTimingField(
    timing: TaskTiming,
    onTimingChange: (TaskTiming) -> Unit,
    modifier: Modifier = Modifier,
    warnIfPast: Boolean = true,
) {
    val zone = ZoneId.systemDefault()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val date = timing.date

    val reminderAt = timing.reminderAt(zone)
    val isPast = timing.effectiveRepeat == ReminderRepeat.NONE && reminderAt != null &&
        reminderAt <= System.currentTimeMillis()
    val warning = when {
        reminderAt == null -> null
        isPast && warnIfPast -> R.string.reminder_in_past
        isPast -> null
        !NotificationManagerCompat.from(context).areNotificationsEnabled() -> R.string.reminder_notifications_off
        else -> null
    }

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimingChip(
                label = if (date == null) stringResource(R.string.task_no_date) else dateLabel(date),
                icon = { Icon(Icons.Outlined.Event, null, Modifier.size(InputChipDefaults.IconSize)) },
                onClick = { showDatePicker = true },
                onClear = if (date != null) ({ onTimingChange(TaskTiming(date = null)) }) else null,
                clearLabel = stringResource(R.string.task_clear_date),
            )
            if (date != null) {
                RepeatChip(
                    date = date,
                    repeat = timing.repeat,
                    onRepeatChange = { onTimingChange(timing.copy(repeat = it)) },
                )
                TimingChip(
                    label = timing.time?.format(TIME) ?: stringResource(R.string.reminder_set),
                    icon = { Icon(Icons.Outlined.Notifications, null, Modifier.size(InputChipDefaults.IconSize)) },
                    onClick = { showTimePicker = true },
                    onClear = if (timing.time != null) ({ onTimingChange(timing.copy(time = null)) }) else null,
                    clearLabel = stringResource(R.string.reminder_clear),
                )
            }
        }
        if (warning != null) {
            Text(
                text = stringResource(warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (isPast) {
            Text(
                text = stringResource(R.string.reminder_already_passed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = date ?: LocalDate.now(zone),
            onConfirm = { picked ->
                onTimingChange(timing.copy(date = picked))
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker && date != null) {
        TimePickerDialog(
            title = stringResource(R.string.reminder_pick_time),
            initialTime = timing.time ?: ReminderTime.suggestedTime(date, LocalDateTime.now(zone)),
            onConfirm = { time ->
                onTimingChange(timing.copy(time = time))
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun TimingChip(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    clearLabel: String,
) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon,
        trailingIcon = onClear?.let { clear ->
            {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = clearLabel,
                    modifier = Modifier
                        .size(InputChipDefaults.IconSize)
                        .clickable(onClick = clear),
                )
            }
        },
    )
}

@Composable
private fun RepeatChip(
    date: LocalDate,
    repeat: ReminderRepeat,
    onRepeatChange: (ReminderRepeat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InputChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(repeatLabel(repeat, date)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
                    contentDescription = stringResource(R.string.repeat_title),
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(InputChipDefaults.IconSize))
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReminderRepeat.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(repeatLabel(option, date)) },
                    onClick = {
                        onRepeatChange(option)
                        expanded = false
                    },
                    trailingIcon = if (option == repeat) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = ReminderTime.dateToPickerMillis(initialDate))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(ReminderTime.pickerMillisToDate(it)) } },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        // O formato padrão ("24 de out. de 2026") não cabe no título em pt-BR; o ano já aparece no cabeçalho do mês.
        DatePicker(
            state = state,
            dateFormatter = remember { DatePickerDefaults.dateFormatter(selectedDateSkeleton = "MMMd") },
        )
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd/MM")
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "Hoje", "Amanhã", "Ontem", "qua., 30/10" ou, em outro ano, "30/10/2027". */
@Composable
fun dateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> stringResource(R.string.reminder_today)
    date == today.plusDays(1) -> stringResource(R.string.reminder_tomorrow)
    date == today.minusDays(1) -> stringResource(R.string.yesterday)
    date.year == today.year -> date.format(SHORT_DATE)
    else -> date.format(FULL_DATE)
}

/** "Não repete", "Todo dia", "Dias úteis", "Toda segunda", "Todo dia 24" — relativo à data da tarefa. */
@Composable
fun repeatLabel(repeat: ReminderRepeat, date: LocalDate): String = when (repeat) {
    ReminderRepeat.NONE -> stringResource(R.string.repeat_none)
    ReminderRepeat.DAILY -> stringResource(R.string.repeat_daily)
    ReminderRepeat.WEEKDAYS -> stringResource(R.string.repeat_weekdays)
    // Por dia da semana: o gênero muda em português ("toda segunda", "todo sábado").
    ReminderRepeat.WEEKLY -> stringArrayResource(R.array.repeat_weekly)[date.dayOfWeek.value - 1]
    ReminderRepeat.MONTHLY -> stringResource(R.string.repeat_monthly, date.dayOfMonth)
}
