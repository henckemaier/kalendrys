@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Painel de criação de tarefa, usado pela lista e pelo calendário. A data já vem preenchida
 * com [initialDate] (hoje, ou o dia escolhido no calendário) e pode ser trocada ou removida.
 */
@Composable
internal fun NewTaskSheet(
    onSave: (title: String, notes: String, timing: TaskTiming, location: String) -> Unit,
    onDismiss: () -> Unit,
    initialDate: LocalDate? = LocalDate.now(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    // Guardados separadamente: tipos simples sobrevivem à rotação sem um Saver próprio.
    var epochDay by rememberSaveable { mutableStateOf(initialDate?.toEpochDay()) }
    var minuteOfDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var repeat by rememberSaveable { mutableStateOf(ReminderRepeat.NONE) }
    // Config da recorrência personalizada (ver TaskTiming/ReminderRecurrence).
    var intervalEvery by rememberSaveable { mutableStateOf(1) }
    var intervalUnit by rememberSaveable { mutableStateOf<RecurrenceUnit?>(null) }
    var byWeekdays by rememberSaveable { mutableStateOf(0) }
    val timing = TaskTiming(
        date = epochDay?.let(LocalDate::ofEpochDay),
        time = minuteOfDay?.let { LocalTime.of(it / 60, it % 60) },
        repeat = repeat,
        intervalEvery = intervalEvery,
        intervalUnit = intervalUnit,
        byWeekdays = byWeekdays,
    )
    val focusRequester = remember { FocusRequester() }

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.new_task), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            TaskTimingField(
                timing = timing,
                onTimingChange = { changed ->
                    epochDay = changed.date?.toEpochDay()
                    minuteOfDay = changed.time?.let { it.hour * 60 + it.minute }
                    repeat = changed.repeat
                    intervalEvery = changed.intervalEvery
                    intervalUnit = changed.intervalUnit
                    byWeekdays = changed.byWeekdays
                },
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            LocationField(value = location, onValueChange = { location = it })
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { hideThen(onDismiss) }) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { hideThen { onSave(title, notes, timing, location) } },
                    enabled = title.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

/** Local opcional (endereço ou nome do lugar); [trailing] serve para o atalho do mapa no detalhe. */
@Composable
internal fun LocationField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.task_location_label)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null) },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
    )
}
