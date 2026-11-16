package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.CollapsingFab
import com.kalendrys.app.ui.components.ConfirmDialog
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.GroupedCard
import com.kalendrys.app.ui.components.ListRow
import com.kalendrys.app.ui.components.ProgressLine
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.rememberFabExpanded
import com.kalendrys.app.ui.shopping.ShoppingListsViewModel.Event
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

/**
 * Aba Listas (ADR-0001, fatia 3). Tela piloto dos componentes da v1.7.0 (ADR-0002, 12.4 e 12.9 E2):
 * AppScaffold, AppTopBar, CollapsingFab, GroupedCard + ListRow, ProgressLine e EmptyState com ação.
 * Gestos do cartão de lista (12.4): toque abre; menu com Renomear e Excluir (com confirmação).
 */
@Composable
fun ShoppingListsScreen(
    onOpenList: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ShoppingListsViewModel = viewModel(factory = ShoppingListsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpanded(listState)
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

    val settingsLabel = stringResource(R.string.settings_title)
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tab_lists),
                actions = listOf(TopBarAction(settingsLabel, Icons.Outlined.Settings, onClick = onOpenSettings)),
            )
        },
        floatingActionButton = {
            CollapsingFab(
                text = stringResource(R.string.shopping_list_create),
                expanded = fabExpanded,
                onClick = { creating = true },
            )
        },
        hasFab = true,
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            state.lists.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ShoppingCart,
                title = stringResource(R.string.shopping_lists_empty_title),
                message = stringResource(R.string.shopping_lists_empty_message),
                actionLabel = stringResource(R.string.shopping_list_create),
                onAction = { creating = true },
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = padding.plus(horizontal = Spacing.l, top = Spacing.xs),
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

/** Soma margens próprias da tela ao preenchimento do [AppScaffold] (insets e folga do FAB). */
@Composable
private fun PaddingValues.plus(horizontal: Dp, top: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + horizontal,
        end = calculateEndPadding(direction) + horizontal,
        top = calculateTopPadding() + top,
        bottom = calculateBottomPadding(),
    )
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
    GroupedCard(index = index, count = count, onClick = onOpen) {
        Column {
            ListRow(
                title = summary.name,
                supporting = rowSupporting(summary),
                trailing = { RowMenu(name = summary.name, onRename = onRename, onDelete = onDelete) },
            )
            // Lista vazia: a ProgressLine não desenha nada (regra única em domain/ShoppingProgress).
            ProgressLine(
                progress = summary.progress,
                modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m),
            )
        }
    }
}

/** Valor, se houver, e quem usa a lista (ou "Avulsa"); vazia diz isso em vez de mostrar 0/0. */
@Composable
private fun rowSupporting(summary: ShoppingListSummary): String = buildList {
    if (!summary.progress.isVisible) add(stringResource(R.string.list_card_empty))
    if (summary.totalCents > 0) add(MoneyFormat.formatCents(summary.totalCents))
    add(
        if (summary.owners.isEmpty()) {
            stringResource(R.string.shopping_list_standalone)
        } else {
            ownersLabel(summary.owners)
        },
    )
}.joinToString(" · ")

/** Menu do cartão (ADR-0002, 12.4): ações raras com rótulo, fora do toque principal. */
@Composable
private fun RowMenu(name: String, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.shopping_list_options_named, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shopping_list_rename)) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shopping_list_delete)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
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
    ConfirmDialog(
        title = stringResource(R.string.shopping_list_delete_title),
        text = message,
        confirmLabel = stringResource(R.string.delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
