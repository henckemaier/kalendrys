package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.domain.MoneyFormat

/**
 * Cartão-resumo de uma lista de compras (ADR-0001): nome, progresso, total e os outros donos.
 * Tocar abre a lista; [onDetach] (quando não nulo) mostra a ação "Desvincular". Reutilizado no
 * editor de rotina (v1.5.0) e no detalhe da tarefa (v1.6.0).
 *
 * [isCurrentOwner] tira da etiqueta de donos quem está mostrando o cartão (a própria rotina ou
 * tarefa), para a etiqueta dizer só com quem mais a lista é compartilhada.
 */
@Composable
fun ShoppingListCard(
    summary: ShoppingListSummary,
    onOpen: () -> Unit,
    onDetach: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isCurrentOwner: (ShoppingListOwner) -> Boolean = { false },
) {
    val progress = summary.progress
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                    .padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    text = summary.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = if (progress.total == 0) {
                    stringResource(R.string.list_card_empty)
                } else {
                    stringResource(R.string.list_card_progress, progress.picked, progress.total)
                }
                val total = summary.totalCents.takeIf { it > 0 }
                    ?.let { stringResource(R.string.list_card_total, MoneyFormat.formatCents(it)) }
                Text(
                    text = listOfNotNull(status, total).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.picked.toFloat() / progress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                val others = summary.owners.filterNot(isCurrentOwner)
                if (others.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.list_card_shared_with, others.joinToString { it.title }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (onDetach != null) {
                IconButton(onClick = onDetach) {
                    Icon(Icons.Outlined.LinkOff, contentDescription = stringResource(R.string.list_card_detach))
                }
            }
        }
    }
}
