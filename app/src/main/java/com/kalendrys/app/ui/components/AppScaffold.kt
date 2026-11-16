package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kalendrys.app.ui.theme.KalendrysTheme

/** Largura máxima do conteúdo (ADR-0002, 12.4): em tablet e paisagem as linhas não esticam. */
val ContentMaxWidth: Dp = 640.dp

/**
 * Esqueleto das telas (ADR-0002, 4.2): barra de topo, FAB, snackbar, insets (inclusive o recorte da
 * câmera em paisagem) e largura máxima de [ContentMaxWidth], centralizada.
 *
 * [content] ocupa a largura toda e recebe o preenchimento para usar como `contentPadding` da lista
 * (não como `Modifier.padding`): assim a lista rola por baixo das barras do sistema e a rolagem pega
 * a tela inteira, sem faixas mortas nas laterais em paisagem. O que passar de [ContentMaxWidth] vai
 * para o preenchimento lateral, dividido entre os dois lados. Com [hasFab], o fundo já inclui
 * [FabClearance], para a última linha nunca ficar sob o FAB. As margens da própria tela entram com
 * [plus].
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    hasFab: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val start = innerPadding.calculateStartPadding(direction)
            val end = innerPadding.calculateEndPadding(direction)
            // Largura que sobra além do máximo, dividida entre as laterais (0 em telefone retrato).
            val excess = ((maxWidth - start - end - ContentMaxWidth) / 2).coerceAtLeast(0.dp)
            val contentPadding = remember(innerPadding, hasFab, direction, excess) {
                PaddingValues(
                    start = start + excess,
                    end = end + excess,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + if (hasFab) FabClearance else 0.dp,
                )
            }
            content(contentPadding)
        }
    }
}

/**
 * Soma as margens da própria tela ao preenchimento do [AppScaffold] (insets, excedente da largura
 * máxima e folga do FAB), para usar como `contentPadding` da lista. [bottom] é o respiro de fim de
 * lista das telas sem FAB (com FAB, a folga já vem do [AppScaffold]).
 */
@Composable
fun PaddingValues.plus(horizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + horizontal,
        end = calculateEndPadding(direction) + horizontal,
        top = calculateTopPadding() + top,
        bottom = calculateBottomPadding() + bottom,
    )
}

@KalendrysPreviews
@Composable
private fun AppScaffoldPreview() {
    KalendrysTheme {
        Surface {
            AppScaffold(
                topBar = { AppTopBar(title = "Listas") },
                floatingActionButton = { CollapsingFab(text = "Nova lista", expanded = true, onClick = {}) },
                hasFab = true,
            ) { padding ->
                Text("Conteúdo", modifier = Modifier.padding(padding))
            }
        }
    }
}
