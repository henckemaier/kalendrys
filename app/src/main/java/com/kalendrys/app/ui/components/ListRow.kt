package com.kalendrys.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/** Altura mínima da linha: o alvo de toque mínimo de acessibilidade. */
val ListRowMinHeight = 48.dp

/**
 * Linha padrão do app (ADR-0002, 4.2): início, título, apoio e fim. Sem estado.
 *
 * - [onClick] torna a linha inteira tocável (com [onClickLabel] para o leitor de tela); `null`
 *   deixa a linha só informativa.
 * - A semântica é mesclada: o leitor de tela lê título, apoio e o que houver no início/fim como um
 *   item só. Ações extras (ex.: as do [SwipeRow]) entram pelo [modifier], no mesmo nó.
 * - Altura mínima de [ListRowMinHeight]; os textos quebram linha em vez de cortar (fonte 2.0).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ListRowMinHeight)
            .semantics(mergeDescendants = true) {}
            .then(interaction)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center) { leading() }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs / 2)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

@KalendrysPreviews
@Composable
private fun ListRowPreview() {
    KalendrysTheme {
        Surface {
            Column {
                ListRow(
                    title = "Mercado",
                    supporting = "3 de 8 marcados",
                    leading = { Icon(Icons.Outlined.ShoppingCart, contentDescription = null) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    onClick = {},
                )
                ListRow(title = "Só título, sem toque")
            }
        }
    }
}
