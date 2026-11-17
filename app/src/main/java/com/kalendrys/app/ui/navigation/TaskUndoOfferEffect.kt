package com.kalendrys.app.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kalendrys.app.R
import com.kalendrys.app.TaskUndoOffers
import com.kalendrys.app.ui.components.showUndo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Único coletor da oferta de Desfazer da exclusão pelo detalhe (v1.8.0, E0-D), chamado pelo
 * `KalendrysNavHost`, que fica sempre composto. Enquanto houver oferta pendente, mostra "Tarefa
 * excluída" com Desfazer (a mesma mensagem e a mesma [showUndo] do deslize na lista) em [hostState],
 * que o [AppScaffold][com.kalendrys.app.ui.components.AppScaffold] da tela à frente desenha.
 *
 * Só limpa a oferta quando a mensagem termina de verdade: Desfazer ([TaskUndoOffers.undo]) ou tempo
 * esgotado/dispensada ([TaskUndoOffers.finish]). Se o coletor sai da composição no meio (girar a
 * tela), a espera é cancelada sem limpar: ao voltar, a oferta ainda pendente aparece de novo, com o
 * tempo reiniciado (NB-4), dentro do prazo da oferta; vencida, é limpa sem mostrar.
 *
 * Só com a Activity À FRENTE (RESUMED no [LocalLifecycleOwner] do NavHost; A1 do Esquadro sobre o
 * NB-E1-A): sair da frente (Home, tela apagada) cancela a espera SEM limpar, como girar. O tempo
 * limite da espera conta só o tempo acordado; por isso, ao voltar, o coletor reinicia e confere o
 * prazo pelo relógio de parede ([TaskUndoOffers.remainingMs]) antes de mostrar. Com a tela à frente,
 * a espera vale no máximo o que RESTA do prazo; vencido, a mensagem sai e a oferta é limpa.
 * O toque em Desfazer não confere o prazo: mensagem visível, toque vale.
 * collectLatest: oferta nova cancela a mensagem da anterior e a substitui.
 *
 * O "só à frente" segue o estado do ciclo de vida (`currentStateFlow`), com o mesmo efeito de
 * `repeatOnLifecycle(RESUMED)`, mas sem trocar o despachante da composição: a espera continua no
 * relógio do Compose (o mesmo dos testes).
 */
@Composable
internal fun TaskUndoOfferEffect(offers: TaskUndoOffers, hostState: SnackbarHostState) {
    val message = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.undo)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(offers, hostState, lifecycle) {
        lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.RESUMED) }
            .distinctUntilChanged()
            .collectLatest { resumed ->
                // Fora da frente: o collectLatest já cancelou a espera anterior, sem limpar a oferta.
                if (!resumed) return@collectLatest
                offers.pending.collectLatest { offer ->
                    if (offer == null) return@collectLatest
                    // Vencida (ex.: voltou horas depois, pelo Home ou com a tela apagada): limpa sem mostrar.
                    val remaining = offers.remainingMs(offer)
                    if (remaining == 0L) {
                        offers.finish(offer)
                        return@collectLatest
                    }
                    // null = o prazo acabou com a mensagem na tela: o cancelamento a tira e a oferta é limpa.
                    // Sair da composição ou da frente cancela sem chegar aqui, e a oferta fica pendente.
                    val undone = withTimeoutOrNull(remaining) { hostState.showUndo(message, undoLabel) }
                    if (undone == true) offers.undo(offer) else offers.finish(offer)
                }
            }
    }
}
