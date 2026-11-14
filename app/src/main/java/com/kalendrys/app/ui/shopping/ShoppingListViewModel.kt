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
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.ShoppingTotals
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
) : ViewModel() {

    private val listId: Long = checkNotNull(savedStateHandle[SHOPPING_LIST_ID_ARG])

    /** Tarefa de origem, quando a tela foi aberta pelo detalhe dela. Usada só na v1.6.0 (oferta P3). */
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

    fun togglePicked(item: ShoppingItem) {
        viewModelScope.launch { repository.setPicked(item, !item.picked) }
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
                ShoppingListViewModel(createSavedStateHandle(), app.shoppingRepository)
            }
        }
    }
}
