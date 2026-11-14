package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.ui.components.groupedItemShape

// Visual dos itens copiado do editor de rotina (que ainda tem a versão inline até a fatia 4).

@Composable
internal fun ShoppingItemRow(
    item: ShoppingItem,
    index: Int,
    count: Int,
    onTogglePicked: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val pickedDescription = stringResource(R.string.shopping_item_picked)
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
                .heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 4.dp),
        ) {
            Checkbox(
                checked = item.picked,
                onCheckedChange = { onTogglePicked() },
                modifier = Modifier.semantics { contentDescription = pickedDescription },
            )
            val qtyBadge = stringResource(R.string.shopping_item_quantity_badge, item.quantity)
            val subtitle = item.priceCents?.let { "$qtyBadge · ${MoneyFormat.formatCents(it)}" } ?: qtyBadge
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.picked) TextDecoration.LineThrough else null,
                    color = if (item.picked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
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
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.shopping_item_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.shopping_item_delete))
            }
        }
    }
}

@Composable
internal fun ShoppingTotalRow(totalCents: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.shopping_total),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormat.formatCents(totalCents),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun ShoppingItemDialog(
    existing: ShoppingItem?,
    onConfirm: (name: String, quantity: Int, priceCents: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var quantityText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.quantity?.toString() ?: "1")
    }
    var priceText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.priceCents?.let { MoneyFormat.centsToInput(it) } ?: "")
    }
    val quantity = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    // Preço só é inválido quando tem texto sem nenhum dígito (ex.: só um separador). Vazio é válido
    // (= sem preço, remoção explícita). Bloquear o inválido impede zerar o preço por engano na edição.
    val priceInvalid = priceText.isNotBlank() && priceText.none { it.isDigit() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.shopping_add_title else R.string.shopping_edit_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shopping_item_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { new -> quantityText = new.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.shopping_item_quantity_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    // Filtra: dígitos + no máximo um separador decimal. Entrada inválida não chega ao parse.
                    value = priceText,
                    onValueChange = { new ->
                        val filtered = MoneyFormat.filterPriceInput(new)
                        // Limita os dígitos: um preço absurdo estouraria o Long do total (quantity * priceCents).
                        if (filtered.count { it.isDigit() } <= MAX_PRICE_DIGITS) priceText = filtered
                    },
                    label = { Text(stringResource(R.string.shopping_item_price_label)) },
                    singleLine = true,
                    isError = priceInvalid,
                    supportingText = if (priceInvalid) {
                        { Text(stringResource(R.string.shopping_price_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Vazio → sem preço (null, explícito). Com dígitos → parse garantido não-nulo.
                    val priceCents = if (priceText.isBlank()) null else MoneyFormat.parseReaisToCents(priceText)
                    onConfirm(name.trim(), quantity, priceCents)
                },
                enabled = name.isNotBlank() && !priceInvalid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Diálogo de nome da lista: criar (sem [initial]) ou renomear. */
@Composable
internal fun ShoppingListNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // TextFieldValue para abrir com o cursor no fim do nome (Renomear) e não no começo.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val name = field.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.copy(text = it.text.take(MAX_LIST_NAME_CHARS)) },
                label = { Text(stringResource(R.string.shopping_list_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth().then(rememberFocusWhenShown()),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Etiqueta dos donos, ex.: "Rotina Mercado, Tarefa Feira". */
@Composable
internal fun ownersLabel(owners: List<ShoppingListOwner>): String =
    owners.map { owner ->
        when (owner.kind) {
            ShoppingListOwner.Kind.ROUTINE -> stringResource(R.string.shopping_list_owner_routine, owner.title)
            ShoppingListOwner.Kind.TASK -> stringResource(R.string.shopping_list_owner_task, owner.title)
        }
    }.joinToString(", ")

// Mesmo teto do editor de rotina: impede estourar o Long de ShoppingTotals.totalCents.
private const val MAX_PRICE_DIGITS = 12
private const val MAX_LIST_NAME_CHARS = 60

/**
 * Foco e teclado no campo assim que ele for posicionado na janela do diálogo (Nova lista, Renomear
 * lista e o "Criar nova" do seletor). Não usa `LaunchedEffect`: o `AlertDialog` compõe numa janela
 * própria e, na recriação (rotação), o efeito rodava antes de o campo estar anexado, e o
 * `requestFocus` estourava "FocusRequester is not initialized". No `onGloballyPositioned` o
 * `focusRequester` já está ligado ao campo. Pede uma vez por composição: após girar, o diálogo
 * recriado volta com foco e teclado.
 */
@Composable
internal fun rememberFocusWhenShown(): Modifier {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var requested by remember { mutableStateOf(false) }
    return Modifier
        .focusRequester(focusRequester)
        .onGloballyPositioned {
            if (!requested) {
                requested = true
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
}
