package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Espaço livre no fim de uma lista que fica sob um FAB, para a última linha terminar inteira acima
 * dele (visível e tocável).
 *
 * Conta (Material3 1.3.x): o Scaffold põe o FAB a `altura do FAB + FabSpacing + inset inferior` do
 * fundo, e o `innerPadding` do Scaffold NÃO inclui o FAB, só a barra inferior/insets. Como as telas
 * aplicam o `innerPadding` com `Modifier.padding` na lista, ela já termina no inset; daqui até o topo
 * do FAB são sempre 56dp (FAB padrão e ExtendedFAB têm altura mínima de 56dp, que não cresce com a
 * escala de fonte) + 16dp de espaçamento. A folga de 32dp separa a linha do FAB.
 *
 * Não somar o `innerPadding.calculateBottomPadding()` aqui quando a lista já recebe o
 * `Modifier.padding(innerPadding)`: o inset seria contado duas vezes.
 */
val FabClearance: Dp = 56.dp + 16.dp + 32.dp

/** `contentPadding` de uma lista sob FAB: laterais e topo da tela, fundo com [FabClearance]. */
fun fabListContentPadding(horizontal: Dp, top: Dp = 0.dp): PaddingValues =
    PaddingValues(start = horizontal, end = horizontal, top = top, bottom = FabClearance)
