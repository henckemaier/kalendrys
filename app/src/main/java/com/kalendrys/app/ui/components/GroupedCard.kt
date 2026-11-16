package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/** Espaço entre os cartões de um grupo: o fundo aparece como uma linha fina entre eles. */
private val GroupGap = 2.dp

/**
 * Cartão de um grupo de linhas (ADR-0002, 4.2), evolução do [groupedItemShape]: cantos grandes nas
 * pontas do grupo e pequenos entre os itens, no fundo `surfaceContainer`. Com [onClick], o cartão
 * inteiro é o alvo do toque. O conteúdo (em geral uma [ListRow]) decide o próprio preenchimento.
 */
@Composable
fun GroupedCard(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = groupedItemShape(index, count)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val cardModifier = modifier
        .fillMaxWidth()
        .padding(bottom = if (index < count - 1) GroupGap else 0.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = cardModifier, content = content)
    } else {
        Surface(shape = shape, color = color, modifier = cardModifier, content = content)
    }
}

@KalendrysPreviews
@Composable
private fun GroupedCardPreview() {
    KalendrysTheme {
        Surface {
            Column(modifier = Modifier.padding(Spacing.l)) {
                repeat(3) { index ->
                    GroupedCard(index = index, count = 3) {
                        Text("Linha ${index + 1}", modifier = Modifier.padding(Spacing.l))
                    }
                }
            }
        }
    }
}
