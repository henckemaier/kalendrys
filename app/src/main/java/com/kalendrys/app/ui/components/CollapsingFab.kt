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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing

/**
 * FAB que recolhe para só o ícone (ADR-0002, 4.2 e 12.4). Sem estado: [expanded] vem da tela, que
 * o deriva da própria rolagem (por exemplo com [rememberFabExpanded]); o componente não conhece a
 * lista. O leitor de tela lê [contentDescription] (padrão: o próprio [text]) aberto ou recolhido,
 * uma vez só; uma descrição mais completa serve quando o rótulo curto omite algo, como o dia
 * selecionado no Calendário.
 */
@Composable
fun CollapsingFab(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Add,
    contentDescription: String = text,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(text) },
        // O ExtendedFAB tira o texto da semântica, aberto ou recolhido (conferido no AppBarsTest): o
        // rótulo fica no próprio botão e o leitor de tela o lê uma vez só nos dois estados.
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

/** Abaixo desta altura de janela (paisagem de telefone) o FAB já nasce recolhido (parecer da E3). */
const val COMPACT_HEIGHT_DP = 480

/**
 * Aberto só no topo da lista E com altura de sobra: em janela baixa o FAB estendido cobriria o
 * conteúdo, então fica sempre recolhido (o rótulo para o leitor de tela é o mesmo).
 */
fun fabExpanded(atTop: Boolean, windowHeightDp: Int): Boolean = atTop && !isCompactHeight(windowHeightDp)

/**
 * Janela baixa (abaixo de [COMPACT_HEIGHT_DP], paisagem de telefone): o corte único que recolhe o
 * FAB e que devolve o título do painel de nova tarefa para a área que rola (O-1 da matriz v1.7.1).
 */
fun isCompactHeight(windowHeightDp: Int): Boolean = windowHeightDp < COMPACT_HEIGHT_DP

/** Altura da janela em dp, inteira, como o corte de [isCompactHeight] a compara. */
@Composable
fun windowHeightDp(): Int {
    val heightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { heightPx.toDp().value.toInt() }
}

/** Aberto no topo da lista, recolhido depois de rolar ou em janela baixa: o padrão das abas. */
@Composable
fun rememberFabExpanded(listState: LazyListState): State<Boolean> {
    val heightDp = windowHeightDp()
    return remember(listState, heightDp) {
        derivedStateOf { fabExpanded(atTop = listState.firstVisibleItemIndex == 0, windowHeightDp = heightDp) }
    }
}

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
