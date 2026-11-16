@file:OptIn(ExperimentalMaterial3Api::class)

package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.ui.components.AppScaffold
import com.kalendrys.app.ui.components.AppTopBar
import com.kalendrys.app.ui.components.EmptyState
import com.kalendrys.app.ui.components.ProgressLine
import com.kalendrys.app.ui.components.QuickAddField
import com.kalendrys.app.ui.components.SectionHeader
import com.kalendrys.app.ui.components.TopBarAction
import com.kalendrys.app.ui.components.plus
import com.kalendrys.app.ui.components.bottomBarInsets
import com.kalendrys.app.ui.shopping.ShoppingListViewModel.Event
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** Tela única de uso da lista (ADR-0001, fatia 2): marcar, adicionar, editar, remover, total. */
@Composable
fun ShoppingListScreen(
    onBack: () -> Unit,
    viewModel: ShoppingListViewModel = viewModel(factory = ShoppingListViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showGestureHint by viewModel.showGestureHint.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showRename by rememberSaveable { mutableStateOf(false) }
    // Texto da adição rápida (C1): sobrevive à rotação; Enter adiciona e o campo segue focado.
    var quickText by rememberSaveable { mutableStateOf("") }
    // Grupo "Marcados (n)": aberto ou recolhido é estado da tela, não vai para o banco (12.4, U6).
    var pickedExpanded by rememberSaveable { mutableStateOf(true) }
    // C3 (O-1 da Coruja): depois de uma adição rápida, a lista rola até o item novo, que é o último
    // dos não marcados. Só quando a inclusão veio do campo (não ao abrir a tela nem ao desmarcar).
    var scrollToNewItem by remember { mutableStateOf(false) }
    LaunchedEffect(state.toPick.size) {
        if (!scrollToNewItem || state.toPick.isEmpty()) return@LaunchedEffect
        scrollToNewItem = false
        // Itens antes dos não marcados: o progresso e, se aparece, a dica.
        val before = 1 + if (showGestureHint) 1 else 0
        val index = before + state.toPick.lastIndex
        // R-1 da Coruja: o efeito roda quando a contagem muda, ANTES do item novo ser medido; com o
        // layout anterior a rolagem ia até o item de antes (um atrasado) ou não rolava. Espera o
        // item novo entrar no layout e só então o traz inteiro para cima do campo.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
        listState.bringFullyIntoView(index)
    }
    // Só o id do item em edição: o item em si é lido do estado (fresco após rotação).
    var editingItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    val removedMessage = stringResource(R.string.shopping_list_item_removed)
    val clearedMessage = stringResource(R.string.shopping_list_picked_cleared)
    val undoLabel = stringResource(R.string.undo)
    val offerActionLabel = stringResource(R.string.shopping_list_offer_complete_action)
    val taskCompletedMessage = stringResource(R.string.task_completed)
    // A mensagem da oferta leva o título da tarefa, então é formatada na hora do evento.
    val res = LocalContext.current.resources
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
        // C1: o campo de adição rápida substitui o FAB e o diálogo de novo item; quantidade e preço
        // se ajustam pelo toque no item.
        bottomBar = {
            if (state.list != null) {
                QuickAddField(
                    value = quickText,
                    onValueChange = { quickText = it },
                    onAdd = { name ->
                        scrollToNewItem = true
                        viewModel.quickAdd(name)
                    },
                    placeholder = stringResource(R.string.shopping_add_item),
                    addLabel = stringResource(R.string.shopping_quick_add_action),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(bottomBarInsets)
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
        },
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
            else -> {
                // A mesma linha nos dois grupos (a marcar e marcados).
                val itemRow: @Composable (ShoppingItem, Int, Int) -> Unit = { item, index, count ->
                    // Gestos da 12.4: deslizar marca/desmarca e remove (com Desfazer); toque edita.
                    // O retorno tátil do deslize é do próprio SwipeRow (não repetir aqui).
                    ShoppingItemSwipeRow(
                        item = item,
                        index = index,
                        count = count,
                        onToggle = { viewModel.togglePicked(item) },
                        onRemove = { viewModel.removeItem(item) },
                        onEdit = { editingItemId = item.id },
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = padding.plus(horizontal = Spacing.l, top = Spacing.s),
                ) {
                    item(key = "progress") {
                        ProgressLine(progress = state.progress, modifier = Modifier.padding(bottom = Spacing.s))
                    }
                    if (showGestureHint) {
                        item(key = "gesture_hint") {
                            ShoppingGestureHint(
                                onDismiss = viewModel::dismissGestureHint,
                                modifier = Modifier.padding(bottom = Spacing.s),
                            )
                        }
                    }
                    itemsIndexed(state.toPick, key = { _, it -> it.id }) { index, item ->
                        Box(Modifier.animateItem()) { itemRow(item, index, state.toPick.size) }
                    }
                    if (state.picked.isNotEmpty()) {
                        item(key = "picked_header") {
                            PickedHeader(
                                count = state.picked.size,
                                expanded = pickedExpanded,
                                onToggle = { pickedExpanded = !pickedExpanded },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (pickedExpanded) {
                            itemsIndexed(state.picked, key = { _, it -> it.id }) { index, item ->
                                Box(Modifier.animateItem()) { itemRow(item, index, state.picked.size) }
                            }
                        }
                    }
                    item(key = "total") {
                        ShoppingTotalRow(totalCents = state.totalCents)
                    }
                }
            }
        }
    }

    // Só edição: incluir é pela adição rápida (C1). Item que sumiu (removido em outro lugar) não abre.
    editingItemId?.let { id -> state.items.firstOrNull { it.id == id } }?.let { editing ->
        ShoppingItemDialog(
            existing = editing,
            onConfirm = { name, quantity, priceCents ->
                viewModel.updateItem(editing, name, quantity, priceCents)
                editingItemId = null
            },
            onDismiss = { editingItemId = null },
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

/**
 * Cabeçalho do grupo "Marcados (n)" (ADR-0002, 12.4, U6): toca para recolher ou mostrar os itens
 * marcados. Lido como cabeçalho, com a contagem e a ação de abrir/recolher no mesmo nó.
 */
@Composable
private fun PickedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val actionLabel = stringResource(
        if (expanded) R.string.shopping_list_picked_collapse else R.string.shopping_list_picked_expand,
    )
    // Um nó só (C2 do Esquadro): o texto "Marcados", a contagem, o cabeçalho e a ação de recolher.
    SectionHeader(
        title = stringResource(R.string.shopping_list_picked_group),
        count = count,
        contentPadding = PaddingValues(top = Spacing.m, bottom = Spacing.s),
        onClick = onToggle,
        onClickLabel = actionLabel,
        trailing = {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

/**
 * Traz o item [index] INTEIRO para a área visível, com a borda de baixo logo acima do fim útil da
 * lista (descontado o preenchimento inferior, onde ficam o campo de adição rápida e o teclado). Se
 * já está inteiro, não rola. Se ainda nem foi medido (fora da tela), pula até ele e o assenta no
 * fim, para a pessoa continuar vendo os itens de antes.
 */
private suspend fun LazyListState.bringFullyIntoView(index: Int) {
    fun item() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    fun usableEnd() = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val measured = item()
    if (measured == null) {
        scrollToItem(index)
        val atTop = item() ?: return
        // No topo da área; desce o conteúdo para o item terminar no fim útil (rolagem para trás).
        val back = usableEnd() - (atTop.offset + atTop.size)
        if (back > 0) scrollBy(-back.toFloat())
        return
    }
    val overflow = measured.offset + measured.size - usableEnd()
    if (overflow > 0) animateScrollBy(overflow.toFloat())
}
