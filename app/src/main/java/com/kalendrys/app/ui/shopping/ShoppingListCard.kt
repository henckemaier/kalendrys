package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.MoneyFormat
import com.kalendrys.app.ui.theme.Spacing

/**
 * Cartão-resumo da lista de uma tarefa (ADR-0001; v1.10.0, ADR-0002 16.10.4): nome, progresso e total.
 * Tocar abre a lista; [onDelete] (quando não nulo) mostra a ação "Excluir lista", que quem chama
 * confirma antes ([DeleteListDialog]). A lista é de uma tarefa só: não há mais etiqueta de outros donos
 * nem "Desvincular".
 */
@Composable
fun ShoppingListCard(
    summary: ShoppingListSummary,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val progress = summary.progress
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(start = Spacing.l, end = Spacing.xs, top = Spacing.m, bottom = Spacing.m),
        ) {
            Icon(
                Icons.Outlined.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.l, end = Spacing.s),
            ) {
                Text(
                    text = summary.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = if (!progress.isVisible) {
                    stringResource(R.string.list_card_empty)
                } else {
                    pluralStringResource(R.plurals.list_card_progress, progress.pluralQuantity, progress.picked, progress.total)
                }
                val total = summary.totalCents.takeIf { it > 0 }
                    ?.let { stringResource(R.string.list_card_total, MoneyFormat.formatCents(it)) }
                Text(
                    text = listOfNotNull(status, total).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.isVisible) {
                    LinearProgressIndicator(
                        progress = { progress.picked.toFloat() / progress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.s),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.shopping_list_delete))
                }
            }
        }
    }
}
