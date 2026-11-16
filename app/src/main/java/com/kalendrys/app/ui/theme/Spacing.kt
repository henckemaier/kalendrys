package com.kalendrys.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Espaçamentos do app (ADR-0002, 12.3), em passos de 4dp. As telas usam estes nomes em vez de
 * números soltos; a migração acontece tela a tela, junto com a casca (E3).
 */
object Spacing {
    /** Entre ícone e texto, ou entre linhas de um grupo. */
    val xs = 4.dp

    /** Entre elementos relacionados. */
    val s = 8.dp

    /** Entre blocos de um cartão. */
    val m = 12.dp

    /** Margem lateral das telas e preenchimento dos cartões. */
    val l = 16.dp

    /** Entre seções. */
    val xl = 24.dp

    /** Respiro grande (estados vazios, fim de lista). */
    val xxl = 32.dp
}
