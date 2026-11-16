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
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.domain.NewTaskList
import com.kalendrys.app.ui.components.isCompactHeight
import com.kalendrys.app.ui.components.windowHeightDp
import com.kalendrys.app.ui.shopping.ShoppingListPicker
import kotlinx.coroutines.launch

/**
 * Painel de criação de tarefa, usado pela aba Tarefas e pelo Calendário. SEM estado próprio
 * (v1.7.1): mostra o [draft] guardado no NewTaskDraftStore e devolve as mudanças por [onChange].
 *
 * Fechar pelo voltar, tocando fora ou arrastando para baixo ([onDismiss]) GUARDA o rascunho;
 * [onCancel] o descarta; [onSave] grava a tarefa e o descarta (quem chama decide, aqui só avisa).
 *
 * [lists] liga a opção de anexar lista de compras ("Criar nova" / "Usar lista existente"); `null`
 * esconde a opção.
 */
@Composable
internal fun NewTaskSheet(
    draft: NewTaskDraft,
    onChange: ((NewTaskDraft) -> NewTaskDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    lists: List<ShoppingListSummary>? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showListPicker by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // Título fixo só em janela de altura normal (L29: rolando, o foco no campo Título o escondia sob
    // a alça). Em janela baixa (o mesmo corte que recolhe o FAB) ele volta para a área que rola, como
    // na v1.7.0: fixo, ele tirava a altura que o teclado deixa e o Salvar ficava fora de alcance (O-1).
    val fixedHeader = !isCompactHeight(windowHeightDp())
    val header = @Composable { modifier: Modifier ->
        Text(stringResource(R.string.new_task), style = MaterialTheme.typography.titleLarge, modifier = modifier)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (fixedHeader) {
            header(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!fixedHeader) header(Modifier)
            OutlinedTextField(
                value = draft.title,
                onValueChange = { value -> onChange { it.copy(title = value) } },
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
                timing = draft.timing,
                onTimingChange = { changed -> onChange { it.withTiming(changed) } },
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { value -> onChange { it.copy(notes = value) } },
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            LocationField(value = draft.location, onValueChange = { value -> onChange { it.copy(location = value) } })
            if (lists != null) {
                NewTaskListField(
                    choice = draft.list,
                    lists = lists,
                    onAdd = { showListPicker = true },
                    onClear = { onChange { it.copy(list = null) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { hideThen(onCancel) }) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = { hideThen(onSave) }, enabled = draft.canSave) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    if (showListPicker && lists != null) {
        ShoppingListPicker(
            lists = lists,
            suggestedName = draft.title,
            onCreate = { name ->
                onChange { it.copy(list = NewTaskList.New(name)) }
                showListPicker = false
            },
            onPick = { id ->
                onChange { it.copy(list = NewTaskList.Existing(id)) }
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
        shape = MaterialTheme.shapes.medium,
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
