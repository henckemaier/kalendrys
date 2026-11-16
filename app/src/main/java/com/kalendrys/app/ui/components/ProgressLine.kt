package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import com.kalendrys.app.R
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Barra de progresso com o texto "3/8" ao lado (ADR-0002, 4.2). Lista vazia não desenha nada:
 * a regra é a de [ShoppingProgress.isVisible], a mesma do widget, das notificações e das linhas.
 *
 * O leitor de tela ouve a frase inteira ("2 de 3 marcados") e o valor da barra, não "2 barra 3".
 */
@Composable
fun ProgressLine(
    progress: ShoppingProgress,
    modifier: Modifier = Modifier,
) {
    if (!progress.isVisible) return
    val description = pluralStringResource(
        R.plurals.list_card_progress,
        progress.pluralQuantity,
        progress.picked,
        progress.total,
    )
    val fraction = (progress.picked.toFloat() / progress.total).coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = progress.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@KalendrysPreviews
@Composable
private fun ProgressLinePreview() {
    KalendrysTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
                modifier = Modifier.padding(Spacing.l),
            ) {
                ProgressLine(ShoppingProgress(picked = 3, total = 8))
                ProgressLine(ShoppingProgress(picked = 8, total = 8))
                // Lista vazia: nada aparece.
                ProgressLine(ShoppingProgress.EMPTY)
            }
        }
    }
}
