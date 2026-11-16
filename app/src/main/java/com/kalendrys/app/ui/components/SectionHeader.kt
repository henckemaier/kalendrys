package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Título de seção de uma lista (ADR-0002, 4.2), com a contagem opcional ao lado: "Marcados 3".
 * Marcado como cabeçalho para o leitor de tela pular de seção em seção; título e contagem são lidos
 * juntos. A margem lateral é a das telas ([Spacing.l]); o chamador só posiciona.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@KalendrysPreviews
@Composable
private fun SectionHeaderPreview() {
    KalendrysTheme {
        Surface {
            Column {
                SectionHeader(title = "Hoje")
                SectionHeader(title = "Marcados", count = 3)
            }
        }
    }
}
