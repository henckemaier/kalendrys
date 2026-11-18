package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
 * Não guardar o preenchimento do Scaffold em `remember`: é um objeto vivo, cujos valores mudam sem o
 * objeto mudar (R-1 da E4).
 * Com cabeçalho alto (paisagem), a folga do FAB é limitada para sempre sobrar uma linha visível.
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
        snackbarHost = { ScreenSnackbars(own = snackbarHostState) },
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
            val top = innerPadding.calculateTopPadding()
            val bottomInset = innerPadding.calculateBottomPadding()
            val contentPadding = PaddingValues(
                start = start + excess,
                end = end + excess,
                top = top,
                bottom = bottomInset + if (hasFab) fabClearanceFor(maxHeight - top - bottomInset) else 0.dp,
            )
            content(contentPadding)
        }
    }
}

/**
 * Slot de mensagens da tela: a do app ([LocalAppSnackbarHostState]) em cima e a da própria tela
 * ([own]) embaixo, uma acima da outra, e o slot inteiro acima do FAB (Material3). A do app só é desenhada
 * com a tela à frente (RESUMED): na transição de volta o detalhe e a origem ficam compostos juntos e,
 * sem isso, os dois desenhariam a mesma mensagem.
 */
@Composable
private fun ScreenSnackbars(own: SnackbarHostState?) {
    val app = LocalAppSnackbarHostState.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val showApp = app != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (!showApp && own == null) return
    Column {
        if (showApp) SnackbarHost(app!!)
        own?.let { SnackbarHost(it) }
    }
}

/**
 * Insets de quem vai no [AppScaffold] `bottomBar`: barra do sistema e laterais. O teclado já foi
 * consumido pelo AppScaffold (a casca encolhe acima dele), então não é somado de novo aqui.
 */
val bottomBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** Altura mínima de uma linha que sempre fica visível acima da folga do FAB. */
private val MinVisibleRow: Dp = 48.dp

/**
 * Folga do FAB no fim da lista, limitada ao espaço que sobra entre as barras ([available]): nunca maior
 * que esse espaço menos uma linha. Sem o limite, numa tela com cabeçalho alto em paisagem (aba
 * Rotinas, com a faixa da semana e os filtros presos na barra) a folga fixa era maior que a área útil
 * e a lista rolava até o último item sumir sob a barra (S-1 da Coruja). Em telefone retrato vale a
 * folga inteira ([FabClearance]).
 */
internal fun fabClearanceFor(available: Dp): Dp =
    FabClearance.coerceAtMost((available - MinVisibleRow).coerceAtLeast(0.dp))

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
