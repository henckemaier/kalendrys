@file:OptIn(ExperimentalLayoutApi::class)

package com.kalendrys.app.ui.routineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.DurationFormat
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineKind
import com.kalendrys.app.ui.routines.minuteOfDayLabel

/*
 * Seções do editor de rotina (v1.9.0, ADR-0002 15.5; ordem da 16.10.5 na v1.10.0): Título; Quando
 * (início, término ou duração, dias, a cada N semanas); Lembrar; Tipo; Exercícios (só no tipo
 * Exercício); Aparência (ícone e cor); e, recolhidos em "Mais opções", Vigência e Observações. Cada
 * seção recebe os campos mostrados ([RoutineDraft], só leitura) e devolve eventos; quem grava é o
 * ViewModel (rascunho na criação, mudança na edição).
 */

@Composable
internal fun TitleSection(title: String, showError: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = title,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.task_title_label)) },
        singleLine = true,
        isError = showError,
        supportingText = if (showError) {
            { Text(stringResource(R.string.routine_title_required)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    )
}

/** Quando: início, término (ou duração), dias e a cada N semanas. */
@Composable
internal fun WhenSection(
    fields: RoutineDraft,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onDuration: (Int) -> Unit,
    onToggleDay: (java.time.DayOfWeek) -> Unit,
    onSetDays: (Int) -> Unit,
    onWeekInterval: (Int) -> Unit,
) {
    SectionTitle(stringResource(R.string.routine_when))
    TimeRow(
        label = stringResource(R.string.routine_start),
        value = minuteOfDayLabel(fields.startMinute),
        index = 0,
        onClick = onPickStart,
    )
    TimeRow(
        label = stringResource(R.string.routine_end),
        value = when {
            fields.durationMinutes == 0 -> "—"
            fields.endsNextDay -> stringResource(R.string.routine_end_next_day, minuteOfDayLabel(fields.endMinuteOfDay))
            else -> minuteOfDayLabel(fields.endMinuteOfDay)
        },
        index = 1,
        onClick = onPickEnd,
    )
    Text(
        text = stringResource(R.string.routine_duration),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoutineDraft.DURATION_PRESETS.forEach { minutes ->
            FilterChip(
                selected = fields.durationMinutes == minutes,
                onClick = { onDuration(minutes) },
                label = {
                    Text(if (minutes == 0) stringResource(R.string.routine_no_duration) else DurationFormat.format(minutes))
                },
            )
        }
    }
    Text(
        text = stringResource(R.string.routine_days),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    // O último dia marcado não desmarca (5.3): o ViewModel ignora o toque que zeraria os dias.
    DayPicker(mask = fields.daysOfWeek, onToggle = onToggleDay)
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
                selected = fields.daysOfWeek == mask,
                onClick = { onSetDays(mask) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Spacer(Modifier.size(12.dp))
    WeekIntervalStepper(weeks = fields.weekInterval, onChange = onWeekInterval)
}

@Composable
internal fun RemindSection(remind: Boolean, onChange: (Boolean) -> Unit) {
    RemindRow(checked = remind, onCheckedChange = onChange, modifier = Modifier.padding(top = 24.dp))
}

/**
 * Tipo da rotina (v1.10.0, ADR-0002 16.10.5): Nenhum ou Exercício, um só escolhido. Só o Exercício mostra
 * a seção Exercícios; voltar para Nenhum esconde e guarda os exercícios, sem perguntar.
 */
@Composable
internal fun KindSection(kind: RoutineKind, onSelect: (RoutineKind) -> Unit) {
    SectionTitle(stringResource(R.string.routine_kind))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        RoutineKind.entries.forEach { option ->
            FilterChip(
                selected = kind == option,
                onClick = { onSelect(option) },
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }
}

/** Rótulo do tipo na tela. */
private val RoutineKind.labelRes: Int
    get() = when (this) {
        RoutineKind.NONE -> R.string.routine_kind_none
        RoutineKind.EXERCISE -> R.string.routine_kind_exercise
    }

/** Aparência: ícone e cor. */
@Composable
internal fun AppearanceSection(fields: RoutineDraft, onIcon: (com.kalendrys.app.domain.RoutineIcon) -> Unit, onColor: (RoutineColor) -> Unit) {
    SectionTitle(stringResource(R.string.routine_appearance))
    IconPicker(selected = fields.icon, color = fields.color, onSelect = onIcon)
    Spacer(Modifier.size(16.dp))
    ColorPicker(selected = fields.color, onSelect = onColor)
}

/** Cabeçalho de "Mais opções" (Vigência e Observações), recolhido por padrão. */
@Composable
internal fun MoreOptionsHeader(expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) R.string.routine_more_options_expanded else R.string.routine_more_options_collapsed)
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .semantics {
                heading()
                stateDescription = state
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.more_options),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
    }
}

/** Vigência: o fim só oferece datas a partir do início (o seletor recebe o mínimo, 5.3). */
@Composable
internal fun ValiditySection(
    fields: RoutineDraft,
    onPickStartDate: () -> Unit,
    onPickEndDate: () -> Unit,
    onClearStart: () -> Unit,
    onClearEnd: () -> Unit,
) {
    SectionTitle(stringResource(R.string.routine_validity))
    DateRow(
        label = stringResource(R.string.routine_start_date),
        value = fields.startEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_any),
        index = 0,
        onClick = onPickStartDate,
        onClear = fields.startEpochDay?.let { { onClearStart() } },
    )
    DateRow(
        label = stringResource(R.string.routine_end_date),
        value = fields.endEpochDay?.let(::formatEpochDay) ?: stringResource(R.string.routine_date_none),
        index = 1,
        onClick = onPickEndDate,
        onClear = fields.endEpochDay?.let { { onClearEnd() } },
    )
}

@Composable
internal fun NotesSection(notes: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = notes,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.task_notes_label)) },
        minLines = 2,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    )
}
