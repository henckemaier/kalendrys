package com.kalendrys.app.ui.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ShoppingCompletionOffer
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.ShoppingTotals
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tela de uso de uma lista (ADR-0001, fatia 2). Só o `listId` vem da rota: listas e itens vêm do
 * banco via Flow, e cada edição persiste na hora — por isso o estado sobrevive a rotação e à morte
 * do processo sem guardar nada no [SavedStateHandle].
 */
class ShoppingListViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ShoppingRepository,
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val listId: Long = checkNotNull(savedStateHandle[SHOPPING_LIST_ID_ARG])

    /** Tarefa de origem (detalhe da tarefa ou "Abrir lista" do lembrete): tem prioridade na oferta P3. */
    val originTaskId: Long? = savedStateHandle.get<Long>(SHOPPING_LIST_TASK_ID_ARG)?.takeIf { it != NO_TASK_ID }

    data class UiState(
        val isLoaded: Boolean = false,
        val list: ShoppingList? = null,
        val items: List<ShoppingItem> = emptyList(),
    ) {
        /** Carregou e a lista não existe (excluída, ou id órfão): a tela mostra o estado vazio. */
        val notFound: Boolean get() = isLoaded && list == null
        val progress: ShoppingProgress get() = ShoppingProgress(picked = items.count { it.picked }, total = items.size)
        val totalCents: Long get() = ShoppingTotals.totalCents(items)
        val hasPicked: Boolean get() = items.any { it.picked }
    }

    sealed interface Event {
        /** Item removido; a tela oferece "Desfazer" com o item como estava. */
        data class ItemRemoved(val item: ShoppingItem) : Event

        /** "Desmarcar todos" feito; a tela oferece "Desfazer" com os ids que estavam marcados. */
        data class PickedCleared(val itemIds: List<Long>) : Event

        /** Oferta P3: a marcação deixou a lista completa e há uma tarefa candidata a concluir. */
        data class OfferCompleteTask(val taskId: Long, val taskTitle: String) : Event

        /** Tarefa concluída pela oferta; a tela oferece "Desfazer" (conclusão comum). */
        data class TaskCompleted(val logId: Long) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<UiState> =
        combine(repository.observeList(listId), repository.observeItems(listId)) { list, items ->
            UiState(isLoaded = true, list = list, items = if (list == null) emptyList() else items)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun addItem(name: String, quantity: Int, priceCents: Long?) {
        if (name.isBlank() || uiState.value.notFound) return
        viewModelScope.launch { repository.add(listId, name, quantity, priceCents) }
    }

    fun updateItem(item: ShoppingItem, name: String, quantity: Int, priceCents: Long?) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.update(item, name, quantity, priceCents) }
    }

    /** Devolve o [Job] (marcação + checagem da oferta), útil para quem precisa esperar o fim. */
    fun togglePicked(item: ShoppingItem): Job {
        val picking = !item.picked
        return viewModelScope.launch {
            repository.setPicked(item, picking)
            if (picking) offerCompletionIfComplete()
        }
    }

    /**
     * Regra P3 (ADR-0001): só uma marcação feita nesta tela que deixa a lista completa (não vazia)
     * gera a oferta. A candidata é a tarefa de origem, se pendente; senão, a única tarefa pendente
     * vinculada. Rotinas não recebem oferta. Lido do banco para não depender do estado da tela.
     */
    private suspend fun offerCompletionIfComplete() {
        val items = repository.observeItems(listId).first()
        val progress = ShoppingProgress(picked = items.count { it.picked }, total = items.size)
        if (!progress.isComplete) return
        val taskId = ShoppingCompletionOffer.candidate(progress, originTaskId, taskRepository.linkedTasks(listId))
            ?: return
        val task = taskRepository.observeTask(taskId).first() ?: return
        eventChannel.send(Event.OfferCompleteTask(task.id, task.title))
    }

    /** Aceite da oferta: conclusão comum (tarefa repetida reinicia a lista; o Desfazer restaura). */
    fun completeTask(taskId: Long) {
        viewModelScope.launch {
            val logId = taskRepository.logCompletion(taskId)
            eventChannel.send(Event.TaskCompleted(logId))
        }
    }

    fun undoTaskCompletion(logId: Long) {
        viewModelScope.launch { taskRepository.undoCompletion(logId) }
    }

    fun removeItem(item: ShoppingItem) {
        viewModelScope.launch {
            repository.remove(item)
            eventChannel.send(Event.ItemRemoved(item))
        }
    }

    /** "Desfazer" da remoção: volta o item com o mesmo id, posição e marcação. */
    fun undoRemove(item: ShoppingItem) {
        viewModelScope.launch { repository.reinsert(item) }
    }

    fun clearPicked() {
        viewModelScope.launch {
            // Lido do banco (não do estado da tela) para não perder uma marcação recém-feita.
            val pickedIds = repository.observeItems(listId).first().filter { it.picked }.map { it.id }
            if (pickedIds.isEmpty()) return@launch
            repository.clearPicked(listId)
            eventChannel.send(Event.PickedCleared(pickedIds))
        }
    }

    /** "Desfazer" do "Desmarcar todos": volta a marcar os itens que estavam marcados. */
    fun undoClearPicked(itemIds: List<Long>) {
        viewModelScope.launch { repository.markPicked(itemIds) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameList(listId, name) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                ShoppingListViewModel(createSavedStateHandle(), app.shoppingRepository, app.repository)
            }
        }
    }
}
