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
 * Quantidade para escolher o plural de "marcado(s)" (R.plurals.list_card_progress e
 * list_progress_description): concorda com o TOTAL de itens ("1 de 1 marcado", "0 de 1 marcado",
 * "1 de 3 marcados"). A regra CLDR do pt (pt-BR) põe 0 e 1 em "one", mas o total 0 nunca aparece
 * (lista vazia esconde o progresso) e, se aparecesse, levaria plural; então só o total 1 é singular.
 */
val ShoppingProgress.pluralQuantity: Int get() = if (total == 1) 1 else PLURAL_OTHER

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
        progress.pluralQuantity,
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
