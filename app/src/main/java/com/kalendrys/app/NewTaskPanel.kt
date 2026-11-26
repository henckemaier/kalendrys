package com.kalendrys.app

import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.NewTaskDraft
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Painel de nova tarefa do app (ADR-0002, 14.7; v1.8.0, E6), criado no [KalendrysApp] no molde do
 * [UndoOffers] e usado pela aba Tarefas e pelo Calendário. Tem dentro o [NewTaskDraftStore], que
 * continua sendo o estado puro do rascunho, com as mesmas regras: fechar o painel GUARDA; Cancelar e
 * Salvar DESCARTAM. Só em memória.
 *
 * O PEDIDO de abertura (painel aberto/fechado) NÃO mora aqui: é estado de tela, fica no host.
 *
 * Sem lista existente desde a v1.10.0 (ADR-0002 16.10.4): "Adicionar lista" só marca o rascunho, e a
 * lista nasce ao salvar, com o nome da tarefa. Nenhuma leitura do banco enquanto o painel está aberto.
 *
 * Salvar grava no [scope] do app: quem pediu (a tela) pode fechar na hora sem perder a tarefa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskPanel(
    private val tasks: TaskRepository,
    private val scope: CoroutineScope,
    private val draftStore: NewTaskDraftStore = NewTaskDraftStore(),
) {
    /** O rascunho; `null` = não há rascunho. */
    val draft: StateFlow<NewTaskDraft?> = draftStore.draft

    /** O painel abriu sugerindo [suggestedDate]: cria o rascunho ou devolve o guardado. */
    fun open(suggestedDate: LocalDate?) = draftStore.open(suggestedDate)

    /** Mudança vinda do painel; sem rascunho aberto (já descartado) é ignorada. */
    fun update(transform: (NewTaskDraft) -> NewTaskDraft) = draftStore.update(transform)

    /** Cancelar: descarta o rascunho. */
    fun cancel() {
        draftStore.discard()
    }

    /**
     * Salvar: descarta o rascunho na hora e grava a tarefa no [scope] do app. Título em branco não
     * grava (o rascunho é descartado do mesmo jeito). Com "Adicionar lista", a lista é criada junto com
     * a tarefa e com o nome dela, numa transação só ([TaskRepository.addTask] com `newListName`). Sem
     * rascunho, não faz nada.
     */
    fun save(): Job {
        val saved = draftStore.discard()?.takeIf { it.canSave } ?: return Job().apply { complete() }
        return scope.launch {
            tasks.addTask(
                saved.title, saved.notes, saved.timing, saved.location,
                newListName = saved.title.takeIf { saved.withList },
            )
        }
    }
}
