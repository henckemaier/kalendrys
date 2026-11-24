package com.kalendrys.app.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kalendrys.app.R
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.ui.components.showUndo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Único coletor da oferta de Desfazer do app (exclusões que fecham a tela: tarefa pelo detalhe, v1.8.0
 * E0-D, e rotina pelo editor, P8; excluir e concluir pela lista de Tarefas, E5), chamado pelo
 * `KalendrysNavHost`, que fica sempre composto. Enquanto houver oferta pendente, mostra a mensagem do
 * tipo dela ("Tarefa excluída", "Rotina excluída" ou "Tarefa concluída") com Desfazer ([showUndo]) em
 * [hostState], que o
 * [AppScaffold][com.kalendrys.app.ui.components.AppScaffold] da tela à frente desenha. O coletor só
 * escolhe o texto e chama desfazer ou terminar; quem restaura é o [UndoOffers].
 *
 * Só limpa a oferta quando a mensagem termina de verdade: Desfazer ([UndoOffers.undo]) ou tempo
 * esgotado/dispensada ([UndoOffers.finish]). Se o coletor sai da composição no meio (girar a
 * tela), a espera é cancelada sem limpar: ao voltar, a oferta ainda pendente aparece de novo, com o
 * tempo reiniciado (NB-4), dentro do prazo da oferta; vencida, é limpa sem mostrar.
 *
 * Só com a Activity À FRENTE (RESUMED no [LocalLifecycleOwner] do NavHost; A1 do Esquadro sobre o
 * NB-E1-A): sair da frente (Home, tela apagada) cancela a espera SEM limpar, como girar. O tempo
 * limite da espera conta só o tempo acordado; por isso, ao voltar, o coletor reinicia e confere o
 * prazo pelo relógio de parede ([UndoOffers.remainingMs]) antes de mostrar. Com a tela à frente,
 * a espera vale no máximo o que RESTA do prazo; vencido, a mensagem sai e a oferta é limpa.
 * O toque em Desfazer não confere o prazo: mensagem visível, toque vale.
 * collectLatest: oferta nova (de qualquer tipo) cancela a mensagem da anterior e a substitui.
 *
 * O "só à frente" segue o estado do ciclo de vida (`currentStateFlow`), com o mesmo efeito de
 * `repeatOnLifecycle(RESUMED)`, mas sem trocar o despachante da composição: a espera continua no
 * relógio do Compose (o mesmo dos testes).
 */
@Composable
internal fun UndoOfferEffect(offers: UndoOffers, hostState: SnackbarHostState) {
    val taskMessage = stringResource(R.string.task_deleted)
    val routineMessage = stringResource(R.string.routine_deleted)
    val completedMessage = stringResource(R.string.task_completed)
    val routineCompletedMessage = stringResource(R.string.routine_completed)
    val routineSkippedMessage = stringResource(R.string.today_routine_skipped)
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
                    val message = when (offer.target) {
                        is UndoTarget.DeletedTask -> taskMessage
                        is UndoTarget.DeletedRoutine -> routineMessage
                        is UndoTarget.CompletedTask -> completedMessage
                        is UndoTarget.CompletedRoutine -> routineCompletedMessage
                        is UndoTarget.SkippedRoutine -> routineSkippedMessage
                    }
                    // null = o prazo acabou com a mensagem na tela: o cancelamento a tira e a oferta é limpa.
                    // Sair da composição ou da frente cancela sem chegar aqui, e a oferta fica pendente.
                    val undone = withTimeoutOrNull(remaining) { hostState.showUndo(message, undoLabel) }
                    if (undone == true) offers.undo(offer) else offers.finish(offer)
                }
            }
    }
}
