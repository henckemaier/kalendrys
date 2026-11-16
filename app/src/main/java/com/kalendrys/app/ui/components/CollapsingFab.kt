package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * FAB que recolhe para só o ícone (ADR-0002, 4.2 e 12.4). Sem estado: [expanded] vem da tela, que
 * o deriva da própria rolagem (por exemplo com [rememberFabExpanded]); o componente não conhece a
 * lista. O rótulo [text] é lido pelo leitor de tela aberto ou recolhido, uma vez só.
 */
@Composable
fun CollapsingFab(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Add,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(text) },
        // O ExtendedFAB tira o texto da semântica, aberto ou recolhido (conferido no AppBarsTest): o
        // rótulo fica no próprio botão e o leitor de tela o lê uma vez só nos dois estados.
        modifier = modifier.semantics { contentDescription = text },
    )
}

/** Aberto no topo da lista, recolhido depois de rolar: o padrão das abas. */
@Composable
fun rememberFabExpanded(listState: LazyListState): State<Boolean> =
    remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

@KalendrysPreviews
@Composable
private fun CollapsingFabPreview() {
    KalendrysTheme {
        Surface {
            Column {
                CollapsingFab(text = "Nova lista", expanded = true, onClick = {})
                Spacer(Modifier.height(Spacing.l))
                CollapsingFab(text = "Nova lista", expanded = false, onClick = {})
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
