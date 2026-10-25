@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.domain.ReminderRecurrence
import com.kalendrys.app.domain.ReminderRepeat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class PickerStep { None, Date, Time }

/**
 * Chip "Definir lembrete" que abre calendário → relógio e devolve o epoch millis escolhido,
 * mais o chip de repetição. Em lembretes recorrentes, [reminderAt] é a primeira ocorrência.
 * [warnIfPast] = false trata um lembrete único passado como informação (já tocou), não como erro.
 */
@Composable
fun ReminderField(
    reminderAt: Long?,
    repeat: ReminderRepeat,
    onReminderChange: (Long?) -> Unit,
    onRepeatChange: (ReminderRepeat) -> Unit,
    modifier: Modifier = Modifier,
    warnIfPast: Boolean = true,
) {
    val zone = ZoneId.systemDefault()
    var step by rememberSaveable { mutableStateOf(PickerStep.None) }
    var pickedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val current = reminderAt?.let { ReminderTime.toLocalDateTime(it, zone) }
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    // Recorrente nunca "passa": o chip mostra a próxima ocorrência.
    val isPast = repeat == ReminderRepeat.NONE && reminderAt != null && reminderAt <= now
    val shownAt = reminderAt?.let { ReminderRecurrence.nextTriggerMillis(it, repeat, now, zone) ?: it }

    val warning = when {
        reminderAt == null -> null
        isPast && warnIfPast -> R.string.reminder_in_past
        isPast -> null
        !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
            R.string.reminder_notifications_off
        else -> null
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { step = PickerStep.Date },
                label = {
                    Text(
                        if (shownAt == null) {
                            stringResource(R.string.reminder_set)
                        } else {
                            reminderLabel(shownAt)
                        },
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
            if (reminderAt != null) {
                IconButton(
                    onClick = {
                        onReminderChange(null)
                        onRepeatChange(ReminderRepeat.NONE)
                    },
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.reminder_clear))
                }
            }
        }
        if (current != null) {
            RepeatChip(anchor = current, repeat = repeat, onRepeatChange = onRepeatChange)
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

    when (step) {
        PickerStep.None -> Unit
        PickerStep.Date -> {
            val today = LocalDate.now(zone)
            ReminderDatePickerDialog(
                initialDate = current?.toLocalDate()?.takeIf { it >= today } ?: today,
                onConfirm = { date ->
                    pickedEpochDay = date.toEpochDay()
                    step = PickerStep.Time
                },
                onDismiss = { step = PickerStep.None },
            )
        }
        PickerStep.Time -> {
            val date = pickedEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now(zone)
            TimePickerDialog(
                title = stringResource(R.string.reminder_pick_time),
                initialTime = current?.toLocalTime()
                    ?: ReminderTime.suggestedTime(date, LocalDateTime.now(zone)),
                onConfirm = { time ->
                    onReminderChange(ReminderTime.toEpochMillis(date, time, zone))
                    step = PickerStep.None
                },
                onDismiss = { step = PickerStep.None },
            )
        }
    }
}

@Composable
private fun RepeatChip(
    anchor: LocalDateTime,
    repeat: ReminderRepeat,
    onRepeatChange: (ReminderRepeat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(repeatLabel(repeat, anchor)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Repeat,
                    contentDescription = stringResource(R.string.repeat_title),
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReminderRepeat.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(repeatLabel(option, anchor)) },
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
private fun ReminderDatePickerDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val selectableDates = remember(today) {
        val todayMillis = ReminderTime.dateToPickerMillis(today)
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMillis
            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = ReminderTime.dateToPickerMillis(initialDate),
        selectableDates = selectableDates,
    )

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

/** "Hoje às 18:30", "Amanhã às 09:00", "sáb., 01/11 às 09:00". */
@Composable
fun reminderLabel(epochMillis: Long, today: LocalDate = LocalDate.now()): String {
    val dateTime = ReminderTime.toLocalDateTime(epochMillis, ZoneId.systemDefault())
    val date = dateTime.toLocalDate()
    val day = when {
        date == today -> stringResource(R.string.reminder_today)
        date == today.plusDays(1) -> stringResource(R.string.reminder_tomorrow)
        date.year == today.year -> date.format(SHORT_DATE)
        else -> date.format(FULL_DATE)
    }
    return stringResource(R.string.reminder_at, day, dateTime.format(TIME))
}

/** "Não repete", "Todo dia", "Dias úteis", "Toda segunda", "Todo dia 24" — relativo à primeira ocorrência. */
@Composable
fun repeatLabel(repeat: ReminderRepeat, anchor: LocalDateTime): String = when (repeat) {
    ReminderRepeat.NONE -> stringResource(R.string.repeat_none)
    ReminderRepeat.DAILY -> stringResource(R.string.repeat_daily)
    ReminderRepeat.WEEKDAYS -> stringResource(R.string.repeat_weekdays)
    // Por dia da semana: o gênero muda em português ("toda segunda", "todo sábado").
    ReminderRepeat.WEEKLY -> stringArrayResource(R.array.repeat_weekly)[anchor.dayOfWeek.value - 1]
    ReminderRepeat.MONTHLY -> stringResource(R.string.repeat_monthly, anchor.dayOfMonth)
}

/** Resumo para listas: "Hoje às 16:55" (único) ou "Todo dia às 09:00" (recorrente). */
@Composable
fun reminderSummary(reminderAt: Long, repeat: ReminderRepeat, today: LocalDate = LocalDate.now()): String {
    if (repeat == ReminderRepeat.NONE) return reminderLabel(reminderAt, today)
    val anchor = ReminderTime.toLocalDateTime(reminderAt, ZoneId.systemDefault())
    return stringResource(R.string.reminder_at, repeatLabel(repeat, anchor), anchor.format(TIME))
}
