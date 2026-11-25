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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.today
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.ExerciseFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.AppLocale
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.Dot
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.LocalAppClock
import com.kalendrys.app.ui.components.TimePickerDialog
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.WeekdayToggleRow
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.routineeditor.RoutineEditorViewModel.Event
import com.kalendrys.app.ui.routines.imageVector
import com.kalendrys.app.ui.routines.minuteOfDayLabel
import com.kalendrys.app.ui.routines.routineTone
import com.kalendrys.app.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.collectLatest

/*
 * Controles do editor de rotina (v1.9.0, ADR-0002 15.5: arquivo da tela dividido por seção). Só
 * desenho: nenhum chama o ViewModel; cada seção do [RoutineEditorSections] liga os eventos.
 */

@Composable
internal fun ExerciseItemRow(
    item: ExerciseItem,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Legenda: séries×reps · carga (se houver) · descanso (se houver).
    val subtitle = buildList {
        add(ExerciseFormat.setsReps(item.sets, item.reps))
        item.load?.let { add(it) }
        item.restSeconds?.let { add(ExerciseFormat.restLabel(it)) }
    }.joinToString(" · ")
    Surface(
        shape = groupedItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.exercise_item_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.exercise_item_move_down))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.exercise_item_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.exercise_item_delete))
            }
        }
    }
}

@Composable
internal fun ExerciseItemDialog(
    existing: ExerciseItem?,
    onConfirm: (name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var setsText by rememberSaveable(existing?.id) { mutableStateOf(existing?.sets?.toString() ?: "3") }
    var repsText by rememberSaveable(existing?.id) { mutableStateOf(existing?.reps?.toString() ?: "10") }
    var loadText by rememberSaveable(existing?.id) { mutableStateOf(existing?.load ?: "") }
    var restText by rememberSaveable(existing?.id) { mutableStateOf(existing?.restSeconds?.toString() ?: "") }
    // Séries/reps caem no mínimo 1 quando em branco; carga e descanso em branco = sem valor (null).
    val sets = setsText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val reps = repsText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.exercise_add_title else R.string.exercise_edit_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.exercise_item_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = setsText,
                        onValueChange = { new -> setsText = new.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.exercise_item_sets_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = repsText,
                        onValueChange = { new -> repsText = new.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.exercise_item_reps_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = loadText,
                    // Carga é texto livre (ex. "20kg"), mas com teto: limita a MAX_LOAD_CHARS, como
                    // séries/reps/descanso já limitam os seus. Só barra a entrada; o trim no save fica igual.
                    onValueChange = { loadText = it.take(MAX_LOAD_CHARS) },
                    label = { Text(stringResource(R.string.exercise_item_load_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = restText,
                    onValueChange = { new -> restText = new.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.exercise_item_rest_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val load = loadText.trim().takeIf { it.isNotEmpty() }
                    val rest = restText.toIntOrNull()
                    onConfirm(name.trim(), sets, reps, load, rest)
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal fun timeOf(minute: Int): LocalTime = LocalTime.of(minute % 1440 / 60, minute % 60)

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** Como o bloco vai aparecer na linha do tempo. */
@Composable
internal fun Preview(draft: RoutineDraft) {
    val tone = routineTone(draft.color)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(CircleShape)
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
internal fun IconPicker(selected: RoutineIcon, color: RoutineColor, onSelect: (RoutineIcon) -> Unit) {
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
internal fun ColorPicker(selected: RoutineColor, onSelect: (RoutineColor) -> Unit) {
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
internal fun TimeRow(label: String, value: String, index: Int, onClick: () -> Unit) {
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
internal fun WeekIntervalStepper(weeks: Int, onChange: (Int) -> Unit) {
    val label = if (weeks <= 1) {
        stringResource(R.string.routine_week_interval_weekly)
    } else {
        stringResource(R.string.routine_week_interval_every, weeks)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
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
internal fun DateRow(label: String, value: String, index: Int, onClick: () -> Unit, onClear: (() -> Unit)?) {
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
internal fun RoutineDatePicker(
    initialEpochDay: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** Primeiro dia que o seletor oferece (o fim da vigência só a partir do início, 5.3); null = qualquer. */
    minEpochDay: Long? = null,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initialEpochDay ?: minEpochDay ?: LocalAppClock.current.today().toEpochDay()) * MILLIS_PER_DAY,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                minEpochDay == null || utcTimeMillis / MILLIS_PER_DAY >= minEpochDay

            override fun isSelectableYear(year: Int): Boolean =
                minEpochDay == null || year >= LocalDate.ofEpochDay(minEpochDay).year
        },
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

internal const val MILLIS_PER_DAY = 86_400_000L

// Teto de entrada (B2): a carga é texto livre, limitada para evitar campos gigantes.
internal const val MAX_LOAD_CHARS = 24
internal val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLocale.LOCALE)

internal fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFormatter)

@Composable
internal fun DayPicker(mask: Int, onToggle: (DayOfWeek) -> Unit) {
    WeekdayToggleRow(isSelected = { RoutineDays.contains(mask, it) }, onToggle = onToggle)
}

@Composable
internal fun RemindRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
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
