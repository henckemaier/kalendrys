@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.kalendrys.app.domain.ReminderRepeat
import kotlinx.coroutines.launch

/**
 * Painel de criação de tarefa, usado pela lista e pelo calendário.
 * [initialReminderAt] já preenche o lembrete (ex.: o dia escolhido no calendário).
 */
@Composable
internal fun NewTaskSheet(
    onSave: (title: String, notes: String, reminderAt: Long?, repeat: ReminderRepeat) -> Unit,
    onDismiss: () -> Unit,
    initialReminderAt: Long? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var reminderAt by rememberSaveable { mutableStateOf(initialReminderAt) }
    var repeat by rememberSaveable { mutableStateOf(ReminderRepeat.NONE) }
    // Só o lembrete único precisa estar no futuro; o recorrente segue da próxima ocorrência.
    val reminderInPast = repeat == ReminderRepeat.NONE &&
        reminderAt?.let { it <= System.currentTimeMillis() } == true
    val focusRequester = remember { FocusRequester() }

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            ReminderField(
                reminderAt = reminderAt,
                repeat = repeat,
                onReminderChange = { reminderAt = it },
                onRepeatChange = { repeat = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { hideThen(onDismiss) }) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { hideThen { onSave(title, notes, reminderAt, repeat) } },
                    enabled = title.isNotBlank() && !reminderInPast,
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
