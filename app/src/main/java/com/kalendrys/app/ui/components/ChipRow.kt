package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * Fileira de chips rolável na horizontal (ADR-0002, 4.2), no lugar de `FlowRow`/`Row` de chips.
 *
 * Não corta com fonte grande: a fileira não tem altura fixa (cresce com o chip mais alto) e o que
 * não cabe na largura rola, em vez de ser espremido. O recuo lateral ([contentPadding]) fica dentro
 * da rolagem, então o primeiro e o último chip alinham com a margem da tela e o resto rola até a
 * borda. [key] identifica cada item, como em qualquer lista preguiçosa.
 */
@Composable
fun <T> ChipRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.l),
    chip: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = key) { item -> chip(item) }
    }
}

@KalendrysPreviews
@Composable
private fun ChipRowPreview() {
    KalendrysTheme {
        Surface {
            ChipRow(items = listOf("Todas", "Hoje", "Atrasadas", "Com lembrete", "Concluídas")) { label ->
                FilterChip(selected = label == "Hoje", onClick = {}, label = { Text(label) })
            }
        }
    }
}
