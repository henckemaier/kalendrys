@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.shopping.ShoppingListViewModel.Event
import kotlinx.coroutines.flow.collectLatest

/** Tela única de uso da lista (ADR-0001, fatia 2): marcar, adicionar, editar, remover, total. */
@Composable
fun ShoppingListScreen(
    onBack: () -> Unit,
    viewModel: ShoppingListViewModel = viewModel(factory = ShoppingListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showMenu by remember { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showItemDialog by rememberSaveable { mutableStateOf(false) }
    // Só o id do item em edição: o item em si é lido do estado (fresco após rotação).
    var editingItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    val removedMessage = stringResource(R.string.shopping_list_item_removed)
    val undoLabel = stringResource(R.string.undo)
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
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.list?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.list != null) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.shopping_list_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shopping_list_clear_picked)) },
                                leadingIcon = { Icon(Icons.Outlined.RemoveDone, contentDescription = null) },
                                enabled = state.hasPicked,
                                onClick = {
                                    showMenu = false
                                    viewModel.clearPicked()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shopping_list_rename)) },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state.list != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingItemId = null
                        showItemDialog = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.shopping_add_item)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            !state.isLoaded -> Unit
            state.notFound -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.shopping_list_not_found_title),
                message = stringResource(R.string.shopping_list_not_found_message),
                modifier = Modifier.padding(innerPadding),
            )
            state.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ShoppingCart,
                title = state.list?.name.orEmpty(),
                message = stringResource(R.string.shopping_empty),
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
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
                        onTogglePicked = { viewModel.togglePicked(item) },
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
