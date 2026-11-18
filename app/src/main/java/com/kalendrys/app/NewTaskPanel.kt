package com.kalendrys.app

import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.domain.NewTaskList
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Painel de nova tarefa do app (ADR-0002, 14.7; v1.8.0, E6), criado no [KalendrysApp] no molde do
 * [UndoOffers] e usado pela aba Tarefas e pelo Calendário. Tem dentro o [NewTaskDraftStore], que
 * continua sendo o estado puro do rascunho, com as mesmas regras: fechar o painel GUARDA; Cancelar e
 * Salvar DESCARTAM. Só em memória.
 *
 * O PEDIDO de abertura (painel aberto/fechado) NÃO mora aqui: é estado de tela, fica no host.
 *
 * Lista escolhida no rascunho e excluída no meio some da escolha: este objeto observa as listas SÓ
 * enquanto houver rascunho ([draft] não nulo). Sem rascunho, nenhuma leitura do banco (nem na
 * abertura do app). O Flow do Room só emite a lista de verdade (nunca um vazio provisório), então não
 * apaga uma escolha válida.
 *
 * Salvar grava no [scope] do app: quem pediu (a tela) pode fechar na hora sem perder a tarefa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskPanel(
    private val tasks: TaskRepository,
    private val shopping: ShoppingRepository,
    private val scope: CoroutineScope,
    private val draftStore: NewTaskDraftStore = NewTaskDraftStore(),
    /** De onde vêm as listas (o Room); trocável só para o teste contar quando há observação. */
    private val observeLists: () -> Flow<List<ShoppingListSummary>> = shopping::observeSummaries,
) {
    /** O rascunho; `null` = não há rascunho. */
    val draft: StateFlow<NewTaskDraft?> = draftStore.draft

    /** Listas para o "Usar lista existente" do painel (lê o banco só enquanto alguém coleta). */
    val lists: Flow<List<ShoppingListSummary>> = observeLists()

    init {
        scope.launch {
            draft.map { it != null }
                .distinctUntilChanged()
                .flatMapLatest { open -> if (open) observeLists() else emptyFlow() }
                .collect { lists -> draftStore.keepListsIn(lists.mapTo(HashSet()) { it.id }) }
        }
    }

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
     * grava (o rascunho é descartado do mesmo jeito). Lista existente que sumiu antes de gravar vira
     * "sem lista"; lista nova é criada junto com a tarefa, numa transação só
     * ([TaskRepository.addTask] com `newListName`). Sem rascunho, não faz nada.
     */
    fun save(): Job {
        val saved = draftStore.discard()?.takeIf { it.canSave } ?: return Job().apply { complete() }
        return scope.launch {
            when (val list = saved.list) {
                is NewTaskList.Existing -> tasks.addTask(
                    saved.title, saved.notes, saved.timing, saved.location,
                    shoppingListId = shopping.list(list.listId)?.id,
                )
                is NewTaskList.New -> tasks.addTask(
                    saved.title, saved.notes, saved.timing, saved.location,
                    newListName = list.name,
                )
                null -> tasks.addTask(saved.title, saved.notes, saved.timing, saved.location)
            }
        }
    }
}
