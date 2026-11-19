package com.kalendrys.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kalendrys.app.NewTaskPanel
import com.kalendrys.app.ui.tasks.NewTaskSheet
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers

/**
 * O painel de nova tarefa, num lugar só (v1.8.0, E6, ADR-0002 14.7): hospedado no topo da navegação,
 * fora do NavHost, no molde do [UndoOfferEffect]. O painel é janela própria (bottom sheet), então abre
 * POR CIMA de qualquer destino (aba, detalhe, editor, ajustes) e o voltar fecha primeiro ele.
 *
 * Aberto ou fechado é estado de TELA ([openOn], salvo pela navegação: sobrevive a girar e à morte do
 * processo, e some quando a Activity termina de verdade); rascunho e operações são do app ([panel]).
 * Pedido repetido com o painel aberto não cria um segundo nem apaga o rascunho: [NewTaskPanel.open]
 * devolve o guardado (só a data sugerida pode mudar, se a pessoa ainda não a escolheu).
 *
 * Fechar (voltar, tocar fora, arrastar) GUARDA o rascunho; Cancelar e Salvar o DESCARTAM.
 */
@Composable
internal fun NewTaskHost(panel: NewTaskPanel, openOn: LocalDate?, onClose: () -> Unit) {
    if (openOn == null) return
    LaunchedEffect(openOn) { panel.open(openOn) }
    val lists by panel.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    // Main.immediate: o texto digitado volta ao campo no mesmo quadro, sem saltar o cursor.
    val draft by panel.draft.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    draft?.let {
        NewTaskSheet(
            draft = it,
            onChange = panel::update,
            onSave = {
                panel.save()
                onClose()
            },
            onCancel = {
                panel.cancel()
                onClose()
            },
            onDismiss = onClose,
            lists = lists,
        )
    }
}
