package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.ui.components.HapticEvent
import com.kalendrys.app.ui.components.KalendrysPreviews
import com.kalendrys.app.ui.components.ListRowMinHeight
import com.kalendrys.app.ui.components.SwipeAction
import com.kalendrys.app.ui.components.SwipeRow
import com.kalendrys.app.ui.components.groupedItemShape
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Linha de item da lista de compras com gestos (ADR-0002, 12.4, U4). Sem estado:
 * - deslizar para o fim (direita) marca ou desmarca ([onToggle]); o rótulo da ação acompanha o estado;
 * - deslizar para o início (esquerda) remove ([onRemove]); quem chama mostra o Desfazer;
 * - tocar na linha edita ([onEdit], abre o diálogo de edição).
 *
 * As duas ações de deslize também ficam como ações de acessibilidade, com os mesmos rótulos; o
 * retorno tátil é só o do [SwipeRow] (quem chama não repete).
 *
 * Remover usa `removesRow = true`: o `ShoppingListViewModel.removeItem` sempre apaga o item (não há
 * confirmação nem cancelamento no caminho), então a linha fica fora da tela até a lista atualizar,
 * sem voltar deslizando antes de sumir. Se a exclusão no Room lançar, a exceção sobe no
 * `viewModelScope` (sem tratamento hoje) em vez de deixar a linha escondida.
 *
 * O estado vai como `stateDescription` no nó da linha ("Marcado"/"Não marcado"): o texto riscado
 * não é lido pelo leitor de tela. A linha não vira caixa de marcar (sem `toggleable` nem
 * `Role.Checkbox`): o toque continua sendo Editar. A caixa de marcar segue tocável, como antes
 * (marcar sem deslizar), mas fica fora da leitura para o estado não ser anunciado duas vezes.
 */
@Composable
internal fun ShoppingItemSwipeRow(
    item: ShoppingItem,
    index: Int,
    count: Int,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = groupedItemShape(index, count)
    val toggleAction = SwipeAction(
        label = stringResource(if (item.picked) R.string.shopping_item_unpick else R.string.shopping_item_picked),
        icon = if (item.picked) Icons.Outlined.RadioButtonUnchecked else Icons.Outlined.CheckCircle,
        event = if (item.picked) HapticEvent.Unmark else HapticEvent.Mark,
        onAction = onToggle,
    )
    val removeAction = SwipeAction(
        label = stringResource(R.string.shopping_item_delete),
        icon = Icons.Outlined.Delete,
        event = HapticEvent.Delete,
        removesRow = true,
        onAction = onRemove,
    )
    SwipeRow(
        modifier = modifier.padding(bottom = 2.dp),
        startAction = toggleAction,
        endAction = removeAction,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { actionsModifier ->
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            // Toque, rótulo do toque, estado e ações do deslize no mesmo nó (o que o leitor de tela foca).
            val state = stringResource(
                if (item.picked) R.string.shopping_item_state_picked else R.string.shopping_item_state_unpicked,
            )
            ShoppingItemContent(
                item = item,
                onToggle = onToggle,
                modifier = actionsModifier
                    .semantics { stateDescription = state }
                    .clickable(
                        onClickLabel = stringResource(R.string.shopping_item_edit),
                        onClick = onEdit,
                    ),
            )
        }
    }
}

@Composable
private fun ShoppingItemContent(item: ShoppingItem, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ListRowMinHeight + Spacing.s)
            .padding(start = Spacing.xs, end = Spacing.l),
    ) {
        Checkbox(
            checked = item.picked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.clearAndSetSemantics {},
        )
        val qtyBadge = stringResource(R.string.shopping_item_quantity_badge, item.quantity)
        val subtitle = item.priceCents?.let { "$qtyBadge · ${MoneyFormat.formatCents(it)}" } ?: qtyBadge
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.picked) TextDecoration.LineThrough else null,
                color = if (item.picked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Subtotal da linha (quantity*priceCents); só quando há preço.
        item.priceCents?.let { price ->
            Text(
                text = MoneyFormat.formatCents(price * item.quantity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

@KalendrysPreviews
@Composable
private fun ShoppingItemSwipeRowPreview() {
    KalendrysTheme {
        Surface {
            Column(modifier = Modifier.padding(Spacing.l)) {
                ShoppingItemSwipeRow(
                    item = ShoppingItem(id = 1, listId = 1, name = "Arroz integral", quantity = 2, priceCents = 1290, position = 0, createdAt = 0L),
                    index = 0,
                    count = 2,
                    onToggle = {},
                    onRemove = {},
                    onEdit = {},
                )
                ShoppingItemSwipeRow(
                    item = ShoppingItem(id = 2, listId = 1, name = "Feijão", quantity = 1, picked = true, position = 1, createdAt = 0L),
                    index = 1,
                    count = 2,
                    onToggle = {},
                    onRemove = {},
                    onEdit = {},
                )
            }
        }
    }
}
