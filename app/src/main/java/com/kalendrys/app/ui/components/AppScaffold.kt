package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * [content] recebe o preenchimento para usar como `contentPadding` da lista (não como
 * `Modifier.padding`): assim a lista rola por baixo das barras do sistema. Com [hasFab], o fundo já
 * inclui [FabClearance], para a última linha nunca ficar sob o FAB.
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
        val contentPadding = remember(innerPadding, hasFab, direction) {
            PaddingValues(
                start = innerPadding.calculateStartPadding(direction),
                end = innerPadding.calculateEndPadding(direction),
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + if (hasFab) FabClearance else 0.dp,
            )
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize(),
            ) {
                content(contentPadding)
            }
        }
    }
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
