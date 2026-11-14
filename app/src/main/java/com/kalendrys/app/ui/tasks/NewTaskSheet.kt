@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.shopping.ShoppingListPicker
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Lista de compras escolhida no painel de nova tarefa (ADR-0001, P2). "Criar nova" só cria a lista
 * ao salvar a tarefa: cancelar o painel não deixa lista avulsa para trás.
 */
sealed interface NewTaskList {
    data class Existing(val listId: Long) : NewTaskList
    data class New(val name: String) : NewTaskList
}

/**
 * Id da lista a gravar na nova tarefa: a existente escolhida, ou a nova criada AGORA (no salvar).
 * Regra única para Tarefas e Calendário. Nome em branco não cria lista. Lista existente que foi
 * excluída antes de salvar vira "sem lista" (a tarefa não nasce com vínculo órfão).
 */
suspend fun NewTaskList?.resolveListId(shoppingRepository: ShoppingRepository): Long? = when (this) {
    null -> null
    is NewTaskList.Existing -> shoppingRepository.list(listId)?.id
    is NewTaskList.New -> name.takeIf { it.isNotBlank() }?.let { shoppingRepository.createList(it) }
}

/**
 * Painel de criação de tarefa, usado pela lista e pelo calendário. A data já vem preenchida
 * com [initialDate] (hoje, ou o dia escolhido no calendário) e pode ser trocada ou removida.
 *
 * [lists] liga a opção de anexar lista de compras ("Criar nova" / "Usar lista existente"); `null`
 * esconde a opção (quem chama não oferece listas) e o `list` do [onSave] vem sempre `null`.
 */
@Composable
internal fun NewTaskSheet(
    onSave: (title: String, notes: String, timing: TaskTiming, location: String, list: NewTaskList?) -> Unit,
    onDismiss: () -> Unit,
    initialDate: LocalDate? = LocalDate.now(),
    lists: List<ShoppingListSummary>? = null,
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
    // Lista escolhida, em tipos simples para sobreviver à rotação: uma existente OU o nome de uma nova.
    var listId by rememberSaveable { mutableStateOf<Long?>(null) }
    var newListName by rememberSaveable { mutableStateOf<String?>(null) }
    var showListPicker by rememberSaveable { mutableStateOf(false) }
    // Lista escolhida excluída com o painel aberto: limpa a escolha, para a tarefa não nascer com
    // vínculo órfão. Só depois de a lista ter aparecido nesta composição: logo após a recriação do
    // processo, [lists] chega vazia antes de carregar e não pode apagar uma escolha válida.
    var chosenListSeen by remember { mutableStateOf(false) }
    LaunchedEffect(lists, listId) {
        val chosen = listId ?: return@LaunchedEffect
        val present = lists.orEmpty().any { it.id == chosen }
        if (present) chosenListSeen = true else if (chosenListSeen) {
            listId = null
            chosenListSeen = false
        }
    }
    val listChoice: NewTaskList? = when {
        listId != null -> NewTaskList.Existing(listId!!)
        newListName != null -> NewTaskList.New(newListName!!)
        else -> null
    }
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
            if (lists != null) {
                NewTaskListField(
                    choice = listChoice,
                    lists = lists,
                    onAdd = { showListPicker = true },
                    onClear = {
                        listId = null
                        newListName = null
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { hideThen(onDismiss) }) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { hideThen { onSave(title, notes, timing, location, listChoice) } },
                    enabled = title.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    if (showListPicker && lists != null) {
        ShoppingListPicker(
            lists = lists,
            suggestedName = title,
            onCreate = { name ->
                listId = null
                newListName = name
                showListPicker = false
            },
            onPick = { id ->
                newListName = null
                listId = id
                showListPicker = false
            },
            onDismiss = { showListPicker = false },
        )
    }
}

/** Lista escolhida para a nova tarefa: botão "Adicionar lista" ou a escolha com a ação de remover. */
@Composable
private fun NewTaskListField(
    choice: NewTaskList?,
    lists: List<ShoppingListSummary>,
    onAdd: () -> Unit,
    onClear: () -> Unit,
) {
    val label = when (choice) {
        null -> null
        is NewTaskList.New -> stringResource(R.string.list_card_new_pending, choice.name)
        // A lista pode ter sido excluída enquanto o painel estava aberto: aí some da escolha.
        is NewTaskList.Existing -> lists.firstOrNull { it.id == choice.listId }?.name
    }
    if (label == null) {
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.list_card_add))
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Outlined.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.list_card_remove_choice))
            }
        }
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
