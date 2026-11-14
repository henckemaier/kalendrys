@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.ui.components.fabListContentPadding
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.SettingsButton
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.shopping.ShoppingListsViewModel.Event
import kotlinx.coroutines.flow.collectLatest

/** Aba Listas (ADR-0001, fatia 3). */
@Composable
fun ShoppingListsScreen(
    onOpenList: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ShoppingListsViewModel = viewModel(factory = ShoppingListsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var creating by rememberSaveable { mutableStateOf(false) }
    // Só ids nos diálogos (sobrevivem à rotação); o resumo é lido fresco do estado.
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentOnOpenList by rememberUpdatedState(onOpenList)

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is Event.Created -> currentOnOpenList(event.listId)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_lists)) },
                actions = { SettingsButton(onClick = onOpenSettings) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                // O ExtendedFAB remove o texto da semântica; quem dá o rótulo acessível é o ícone.
                icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.shopping_list_create)) },
                text = { Text(stringResource(R.string.shopping_list_create)) },
            )
        },
    ) { innerPadding ->
        when {
            !state.isLoaded -> Unit
            state.lists.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ShoppingCart,
                title = stringResource(R.string.shopping_lists_empty_title),
                message = stringResource(R.string.shopping_lists_empty_message),
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = fabListContentPadding(horizontal = 16.dp, top = 4.dp),
            ) {
                itemsIndexed(state.lists, key = { _, it -> it.id }) { index, summary ->
                    ShoppingListRow(
                        summary = summary,
                        index = index,
                        count = state.lists.size,
                        onOpen = { onOpenList(summary.id) },
                        onRename = { renamingId = summary.id },
                        onDelete = { deletingId = summary.id },
                    )
                }
            }
        }
    }

    if (creating) {
        ShoppingListNameDialog(
            title = stringResource(R.string.shopping_list_create),
            initial = "",
            onConfirm = {
                viewModel.create(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    // Lista que sumiu (excluída em outro lugar) simplesmente não abre o diálogo.
    renamingId?.let { id -> state.lists.firstOrNull { it.id == id } }?.let { summary ->
        ShoppingListNameDialog(
            title = stringResource(R.string.shopping_list_rename),
            initial = summary.name,
            onConfirm = {
                viewModel.rename(summary.id, it)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }

    deletingId?.let { id -> state.lists.firstOrNull { it.id == id } }?.let { summary ->
        DeleteListDialog(
            summary = summary,
            onConfirm = {
                viewModel.delete(summary.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
private fun ShoppingListRow(
    summary: ShoppingListSummary,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
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
                .clickable(onClick = onOpen)
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    add(stringResource(R.string.shopping_list_progress, summary.progress.picked, summary.progress.total))
                    if (summary.totalCents > 0) add(MoneyFormat.formatCents(summary.totalCents))
                }.joinToString(" · ")
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.progress.isComplete) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = if (summary.owners.isEmpty()) {
                        stringResource(R.string.shopping_list_standalone)
                    } else {
                        ownersLabel(summary.owners)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.shopping_list_rename_named, summary.name),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.shopping_list_delete_named, summary.name),
                )
            }
        }
    }
}

@Composable
private fun DeleteListDialog(
    summary: ShoppingListSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val itemCount = summary.progress.total
    val base = pluralStringResource(R.plurals.shopping_list_delete_message, itemCount, summary.name, itemCount)
    val message = if (summary.owners.isEmpty()) {
        base
    } else {
        base + "\n\n" + stringResource(R.string.shopping_list_delete_owners, ownersLabel(summary.owners))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shopping_list_delete_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
