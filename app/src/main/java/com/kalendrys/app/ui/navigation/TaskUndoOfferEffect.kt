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
 * Único coletor das ofertas de Desfazer da exclusão pelo detalhe (v1.8.0, E0-D), chamado pelo
 * `KalendrysNavHost`, que fica sempre composto. Mostra "Tarefa excluída" com Desfazer (a mesma
 * mensagem e a mesma [showUndo] do deslize na lista) em [hostState], que o [AppScaffold][com.kalendrys.app.ui.components.AppScaffold]
 * da tela à frente desenha. Desfazer restaura pelo [TaskUndoOffers.restore]. collectLatest: uma
 * oferta nova substitui a anterior (a anterior fica excluída).
 */
@Composable
internal fun TaskUndoOfferEffect(offers: TaskUndoOffers, hostState: SnackbarHostState) {
    val message = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(offers, hostState) {
        offers.offers.collectLatest { task ->
            if (hostState.showUndo(message, undoLabel)) offers.restore(task)
        }
    }
}
