package com.kalendrys.app.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.kalendrys.app.R
import com.kalendrys.app.TaskUndoOffers
import com.kalendrys.app.ui.components.showUndo
import kotlinx.coroutines.flow.collectLatest

/**
 * Único coletor da oferta de Desfazer da exclusão pelo detalhe (v1.8.0, E0-D), chamado pelo
 * `KalendrysNavHost`, que fica sempre composto. Enquanto houver oferta pendente, mostra "Tarefa
 * excluída" com Desfazer (a mesma mensagem e a mesma [showUndo] do deslize na lista) em [hostState],
 * que o [AppScaffold][com.kalendrys.app.ui.components.AppScaffold] da tela à frente desenha.
 *
 * Só limpa a oferta quando a mensagem termina de verdade: Desfazer ([TaskUndoOffers.undo]) ou tempo
 * esgotado/dispensada ([TaskUndoOffers.finish]). Se o coletor sai da composição no meio (girar a
 * tela), a espera é cancelada sem limpar: ao voltar, a oferta ainda pendente aparece de novo, com o
 * tempo reiniciado (NB-4). collectLatest: oferta nova cancela a mensagem da anterior e a substitui.
 */
@Composable
internal fun TaskUndoOfferEffect(offers: TaskUndoOffers, hostState: SnackbarHostState) {
    val message = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(offers, hostState) {
        offers.pending.collectLatest { offer ->
            if (offer == null) return@collectLatest
            if (hostState.showUndo(message, undoLabel)) offers.undo(offer) else offers.finish(offer)
        }
    }
}
