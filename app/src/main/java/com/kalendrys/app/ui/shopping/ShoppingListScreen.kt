@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.shopping.ShoppingListViewModel.Event
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

/** Tela única de uso da lista (ADR-0001, fatia 2): marcar, adicionar, editar, remover, total. */
@Composable
fun ShoppingListScreen(
    onBack: () -> Unit,
    viewModel: ShoppingListViewModel = viewModel(factory = ShoppingListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showItemDialog by rememberSaveable { mutableStateOf(false) }
    // Só o id do item em edição: o item em si é lido do estado (fresco após rotação).
    var editingItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    val removedMessage = stringResource(R.string.shopping_list_item_removed)
    val clearedMessage = stringResource(R.string.shopping_list_picked_cleared)
    val undoLabel = stringResource(R.string.undo)
    val offerActionLabel = stringResource(R.string.shopping_list_offer_complete_action)
    val taskCompletedMessage = stringResource(R.string.task_completed)
    // A mensagem da oferta leva o título da tarefa, então é formatada na hora do evento.
    val res = LocalContext.current.resources
    // Retorno tátil leve ao marcar (A5 parcial); o próprio sistema respeita a configuração de toque.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is Event.ItemRemoved -> {
                    val result = snackbarHostState.showSnackbar(
                        message = removedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(event.item)
                }
                is Event.PickedCleared -> {
                    val result = snackbarHostState.showSnackbar(
                        message = clearedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoClearPicked(event.itemIds)
                }
                // P3: oferta sem forçar; some sozinha se ignorada.
                is Event.OfferCompleteTask -> {
                    val result = snackbarHostState.showSnackbar(
                        message = res.getString(R.string.shopping_list_offer_complete, event.taskTitle),
                        actionLabel = offerActionLabel,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.completeTask(event.taskId)
                }
                is Event.TaskCompleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = taskCompletedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoTaskCompletion(event.logId)
                }
            }
        }
    }

    val clearPickedLabel = stringResource(R.string.shopping_list_clear_picked)
    val renameLabel = stringResource(R.string.shopping_list_rename)
    AppScaffold(
        topBar = {
            AppTopBar(
                title = state.list?.name.orEmpty(),
                onBack = onBack,
                // Ações raras, no menu com rótulo; sem lista (não encontrada) não há menu.
                menu = if (state.list == null) {
                    emptyList()
                } else {
                    listOf(
                        TopBarAction(clearPickedLabel, enabled = state.hasPicked, onClick = viewModel::clearPicked),
                        TopBarAction(renameLabel, onClick = { showRename = true }),
                    )
                },
            )
        },
        floatingActionButton = {
            if (state.list != null) {
                CollapsingFab(
                    text = stringResource(R.string.shopping_add_item),
                    expanded = fabExpanded,
                    onClick = {
                        editingItemId = null
                        showItemDialog = true
                    },
                )
            }
        },
        hasFab = state.list != null,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.shopping_list_not_found_title),
                message = stringResource(R.string.shopping_list_not_found_message),
                modifier = Modifier.padding(padding),
            )
            state.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ShoppingCart,
                title = state.list?.name.orEmpty(),
                message = stringResource(R.string.shopping_empty),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = padding.plus(horizontal = Spacing.l, top = Spacing.s),
            ) {
                item(key = "progress") {
                    Text(
                        text = stringResource(
                            R.string.shopping_list_progress,
                            state.progress.picked,
                            state.progress.total,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                    ShoppingItemRow(
                        item = item,
                        index = index,
                        count = state.items.size,
                        onTogglePicked = {
                            if (!item.picked) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.togglePicked(item)
                        },
                        onEdit = {
                            editingItemId = item.id
                            showItemDialog = true
                        },
                        onDelete = { viewModel.removeItem(item) },
                    )
                }
                item(key = "total") {
                    ShoppingTotalRow(totalCents = state.totalCents)
                }
            }
        }
    }

    if (showItemDialog) {
        val editing = editingItemId?.let { id -> state.items.firstOrNull { it.id == id } }
        ShoppingItemDialog(
            existing = editing,
            onConfirm = { name, quantity, priceCents ->
                if (editing == null) {
                    viewModel.addItem(name, quantity, priceCents)
                } else {
                    viewModel.updateItem(editing, name, quantity, priceCents)
                }
                showItemDialog = false
                editingItemId = null
            },
            onDismiss = {
                showItemDialog = false
                editingItemId = null
            },
        )
    }

    if (showRename) {
        state.list?.let { list ->
            ShoppingListNameDialog(
                title = stringResource(R.string.shopping_list_rename),
                initial = list.name,
                onConfirm = {
                    viewModel.rename(it)
                    showRename = false
                },
                onDismiss = { showRename = false },
            )
        }
    }
}
