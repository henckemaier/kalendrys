package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
 * [plus]. Com o teclado aberto, a casca inteira encolhe acima dele (barra do topo fixa).
 * [bottomBar] fica fixo no rodapé (ex.: o [QuickAddField] da tela da lista), logo acima do teclado,
 * e cuida da barra do sistema e das laterais ([bottomBarInsets]); o conteúdo termina acima dele.
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    hasFab: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // O teclado ENCOLHE a casca inteira (parecer do Esquadro, B-1 da E3): a área de rolagem termina
        // acima dele e o campo focado é trazido para cima dele dentro da lista, sem o sistema subir a
        // janela (a Activity usa adjustResize). O teclado fica consumido aqui, então o safeDrawing
        // abaixo não o soma de novo (o Scaffold desconta o que os ancestrais já consumiram).
        modifier = modifier.imePadding(),
        topBar = topBar,
        bottomBar = bottomBar,
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
            // Sem remember: o Scaffold reaproveita o MESMO objeto innerPadding e só troca os valores (ex.:
            // quando o rodapé aparece depois de carregar, ou os insets mudam ao girar); uma cópia
            // guardada com ele como chave ficava velha (R-1 da Coruja: a lista sem o fundo do rodapé).
            val contentPadding = PaddingValues(
                start = start + excess,
                end = end + excess,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + if (hasFab) FabClearance else 0.dp,
            )
            content(contentPadding)
        }
    }
}

/**
 * Insets de quem vai no [AppScaffold] `bottomBar`: barra do sistema e laterais. O teclado já foi
 * consumido pelo AppScaffold (a casca encolhe acima dele), então não é somado de novo aqui.
 */
val bottomBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

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

/**
 * O preenchimento do [AppScaffold] separado para telas com cabeçalho FIXO acima da lista (busca,
 * filtros): o [top] vai para o cabeçalho, que não rola; as laterais ([start], [end], já com as
 * margens da tela) servem ao cabeçalho e à lista; o fundo (insets e folga do FAB) só à lista.
 */
@Immutable
class ScreenPadding(val top: Dp, val start: Dp, val end: Dp, val bottom: Dp) {
    /** `contentPadding` da lista abaixo do cabeçalho: laterais e fundo, sem o topo. */
    val list: PaddingValues get() = PaddingValues(start = start, end = end, bottom = bottom)
}

/** Separa o topo do resto do preenchimento, somando [horizontal] de margem da tela nas laterais. */
@Composable
fun PaddingValues.splitTop(horizontal: Dp = 0.dp): ScreenPadding {
    val direction = LocalLayoutDirection.current
    return ScreenPadding(
        top = calculateTopPadding(),
        start = calculateStartPadding(direction) + horizontal,
        end = calculateEndPadding(direction) + horizontal,
        bottom = calculateBottomPadding(),
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
