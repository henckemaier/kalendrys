@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.taskdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.today
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.LocalAppClock
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.components.longDateLabel
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.weekdayDateLabel
import com.kalendrys.app.ui.shopping.ShoppingListCard
import com.kalendrys.app.ui.shopping.ShoppingListPicker
import com.kalendrys.app.ui.taskdetail.TaskDetailViewModel.Event
import com.kalendrys.app.ui.tasks.LocationField
import com.kalendrys.app.ui.tasks.TaskTimingField
import com.kalendrys.app.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SectionSpacing = 16.dp

@Composable
fun TaskDetailScreen(
    onBack: () -> Unit,
    /**
     * Abre a tela da lista de compras anexada, com esta tarefa como origem (alimenta a oferta
     * "Concluir tarefa", P3). Ligado na navegação com `shoppingListRoute(listId, taskId)`.
     */
    onOpenShoppingList: (listId: Long, taskId: Long) -> Unit = { _, _ -> },
    viewModel: TaskDetailViewModel = viewModel(factory = TaskDetailViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    // Seletor "Criar nova / Usar lista existente" da lista de compras.
    var showListPicker by rememberSaveable { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                Event.Deleted -> currentOnBack()
            }
        }
    }
    // Salvar na hora (15.3): ir para segundo plano grava a digitação pendente. Sair da tela grava pelo
    // onCleared do ViewModel; o voltar da barra também devolve o título vazio ao último válido.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    // Sem Salvar nem "Descartar alterações?" (15.4): o voltar preditivo funciona. No menu, com rótulo,
    // Reverter (só com algo mexido nesta sessão) e Excluir (ADR-0002, 4.2).
    val revertLabel = stringResource(R.string.editor_revert)
    val deleteLabel = stringResource(R.string.delete_task)
    val loaded = state.task != null
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.task_detail_title),
                onBack = {
                    viewModel.leave()
                    onBack()
                },
                menu = if (loaded) {
                    listOfNotNull(
                        TopBarAction(revertLabel, onClick = viewModel::revert).takeIf { state.canRevert },
                        TopBarAction(deleteLabel) { showDeleteDialog = true },
                    )
                } else {
                    emptyList()
                },
            )
        },
    ) { padding ->
        val timing = state.timing
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.task_not_found_title),
                message = stringResource(R.string.task_not_found_message),
                modifier = Modifier.padding(padding),
            )
            timing == null -> Unit
            else -> TaskDetailContent(
                state = state,
                title = viewModel.titleInput,
                notes = viewModel.notesInput,
                location = viewModel.locationInput,
                timing = timing,
                onTitleChange = viewModel::updateTitle,
                onNotesChange = viewModel::updateNotes,
                onTimingChange = viewModel::updateTiming,
                onLocationChange = viewModel::updateLocation,
                onComplete = viewModel::complete,
                onUndoCompletion = viewModel::undoCompletion,
                onOpenShoppingList = { listId -> state.task?.let { onOpenShoppingList(listId, it.id) } },
                onAddShoppingList = { showListPicker = true },
                onDetachShoppingList = viewModel::detachShoppingList,
                // O safeDrawing do AppScaffold já inclui o teclado: a lista rola até o campo focado.
                contentPadding = padding.plus(horizontal = Spacing.l, top = Spacing.s, bottom = Spacing.xl),
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_task_confirm_title)) },
            text = { Text(stringResource(R.string.delete_task_confirm_message)) },
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

    if (showListPicker) {
        ShoppingListPicker(
            lists = state.availableLists,
            suggestedName = state.title,
            onCreate = { name ->
                viewModel.createShoppingList(name)
                showListPicker = false
            },
            onPick = { listId ->
                viewModel.attachShoppingList(listId)
                showListPicker = false
            },
            onDismiss = { showListPicker = false },
        )
    }
}

@Composable
private fun TaskDetailContent(
    state: TaskDetailViewModel.UiState,
    /** Texto dos campos: estado de composição do ViewModel, lido direto (E3-A), nunca o do [state]. */
    title: String,
    notes: String,
    location: String,
    timing: TaskTiming,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTimingChange: (TaskTiming) -> Unit,
    onLocationChange: (String) -> Unit,
    onComplete: () -> Unit,
    onUndoCompletion: () -> Unit,
    onOpenShoppingList: (listId: Long) -> Unit,
    onAddShoppingList: () -> Unit,
    onDetachShoppingList: () -> Unit,
    contentPadding: PaddingValues,
) {
    val zone = state.zone
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "title") {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.task_title_label)) },
                singleLine = true,
                // Vazio não grava (o banco fica com o último título válido) e aparece como erro.
                isError = title.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SectionSpacing),
            )
        }
        item(key = "timing") {
            TaskTimingField(
                timing = timing,
                onTimingChange = onTimingChange,
                // O lembrete da abertura pode ter tocado; só um escolhido aqui precisa estar no futuro.
                warnIfPast = state.reminderChanged,
                modifier = Modifier.padding(bottom = SectionSpacing),
            )
        }
        item(key = "notes") {
            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChange,
                label = { Text(stringResource(R.string.task_notes_label)) },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SectionSpacing),
            )
        }
        item(key = "location") {
            LocationField(
                value = location,
                onValueChange = onLocationChange,
                modifier = Modifier.padding(bottom = SectionSpacing),
                trailing = if (location.isNotBlank()) {
                    {
                        IconButton(onClick = { openInMaps(context, location) }) {
                            Icon(Icons.Outlined.Map, contentDescription = stringResource(R.string.task_open_map))
                        }
                    }
                } else {
                    null
                },
            )
        }
        item(key = "shopping-list") {
            val shoppingList = state.shoppingList
            val taskId = state.task?.id
            Column(modifier = Modifier.padding(bottom = SectionSpacing)) {
                Text(
                    text = stringResource(R.string.list_card_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (shoppingList != null) {
                    ShoppingListCard(
                        summary = shoppingList,
                        onOpen = { onOpenShoppingList(shoppingList.id) },
                        onDetach = onDetachShoppingList,
                        isCurrentOwner = { it.kind == ShoppingListOwner.Kind.TASK && it.id == taskId },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.list_card_hint_task),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    TextButton(onClick = onAddShoppingList) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.list_card_add))
                    }
                }
            }
        }
        item(key = "status") {
            StatusCard(
                doneAt = state.doneAt,
                repeats = state.repeats,
                zone = zone,
                onComplete = onComplete,
                onUndoCompletion = onUndoCompletion,
            )
        }
        state.task?.let { task ->
            item(key = "created") {
                Text(
                    text = stringResource(
                        R.string.task_created_on,
                        longDateLabel(Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
        item(key = "history_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.history.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.history.isEmpty()) {
            item(key = "history_empty") {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(state.history, key = { _, log -> log.id }) { index, log ->
                HistoryRow(
                    log = log,
                    zone = zone,
                    shape = groupedItemShape(index, state.history.size),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/**
 * Situação da tarefa: hoje (se ela se repete) ou de vez (se não se repete). A ação principal conclui
 * ou, concluída, desfaz ESSA conclusão (P6, 15.4); o histórico de outros dias é só leitura.
 */
@Composable
private fun StatusCard(
    doneAt: Long?,
    repeats: Boolean,
    zone: ZoneId,
    onComplete: () -> Unit,
    onUndoCompletion: () -> Unit,
) {
    val done = doneAt != null
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = when {
                    doneAt == null -> stringResource(if (repeats) R.string.status_pending_today else R.string.status_pending)
                    repeats -> stringResource(
                        R.string.status_done_today_at,
                        Instant.ofEpochMilli(doneAt).atZone(zone).format(TIME),
                    )
                    else -> {
                        val at = Instant.ofEpochMilli(doneAt).atZone(zone)
                        stringResource(R.string.status_done_on, historyDayLabel(at.toLocalDate()), at.format(TIME))
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (done) {
                OutlinedButton(onClick = onUndoCompletion) {
                    Text(stringResource(R.string.task_undo_completion))
                }
            } else {
                FilledTonalButton(onClick = onComplete) {
                    Text(stringResource(R.string.complete_today))
                }
            }
        }
    }
}

/** Abre o local no app de mapas (busca pelo texto; não precisa de permissão de localização). */
private fun openInMaps(context: Context, location: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(location)))
    // Sem app de mapas, a busca no navegador; sem nenhum dos dois, avisa em vez de fechar o app.
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(location)))
    val opened = listOf(intent, web).any { candidate ->
        try {
            context.startActivity(candidate)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
    if (!opened) Toast.makeText(context, R.string.task_no_map_app, Toast.LENGTH_SHORT).show()
}

@Composable
private fun HistoryRow(
    log: CompletionLog,
    zone: ZoneId,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val dateTime = Instant.ofEpochMilli(log.completedAt).atZone(zone)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = historyDayLabel(dateTime.toLocalDate()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dateTime.format(TIME),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Hoje", "Ontem", "Quinta-feira, 22 de outubro" ou, em outro ano, "22 de outubro de 2025". */
@Composable
private fun historyDayLabel(date: LocalDate): String {
    val today = LocalAppClock.current.today()
    return when {
        date == today -> stringResource(R.string.reminder_today)
        date == today.minusDays(1) -> stringResource(R.string.yesterday)
        date.year == today.year -> weekdayDateLabel(date)
        else -> longDateLabel(date)
    }
}

