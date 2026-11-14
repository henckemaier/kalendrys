package com.kalendrys.app.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.kalendrys.app.R
import com.kalendrys.app.domain.ShoppingProgress

/**
 * Progresso a mostrar para o dono que aponta para [listId] (ADR-0001, P8): `null` sem lista, com
 * vínculo órfão (lista ausente do mapa) ou com lista vazia, igual ao widget e às notificações
 * ([ShoppingProgress.titleWithProgress]).
 */
fun Map<Long, ShoppingProgress>.visibleProgressFor(listId: Long?): ShoppingProgress? =
    listId?.let(::get)?.takeIf { it.total > 0 }

/**
 * Quantidade para escolher o plural de "marcado(s)" (R.plurals.list_card_progress e
 * list_progress_description): concorda com os itens marcados. A regra CLDR do pt (pt-BR) põe 0 e 1
 * em "one", mas o zero leva plural ("0 de 8 marcados"), então só o 1 escolhe o singular.
 */
val ShoppingProgress.markedQuantity: Int get() = if (picked == 1) 1 else PLURAL_OTHER

/** Qualquer número que a regra do pt põe em "other". */
private const val PLURAL_OTHER = 2

/** Etiqueta compacta "🛒 3/8" para linhas de tarefa e blocos de rotina; o leitor de tela lê a frase inteira. */
@Composable
fun ShoppingProgressTag(
    progress: ShoppingProgress,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val description = pluralStringResource(
        R.plurals.list_progress_description,
        progress.markedQuantity,
        progress.picked,
        progress.total,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(Icons.Outlined.ShoppingCart, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = progress.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}
