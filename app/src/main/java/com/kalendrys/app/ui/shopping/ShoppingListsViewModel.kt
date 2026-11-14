package com.kalendrys.app.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Aba Listas (ADR-0001, fatia 3): todas as listas com progresso, total e donos; criar, renomear, excluir. */
class ShoppingListsViewModel(
    private val repository: ShoppingRepository,
) : ViewModel() {

    data class UiState(
        val isLoaded: Boolean = false,
        val lists: List<ShoppingListSummary> = emptyList(),
    )

    sealed interface Event {
        /** Lista recém-criada: a tela abre a lista para já adicionar itens. */
        data class Created(val listId: Long) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<UiState> =
        repository.observeSummaries()
            .map { UiState(isLoaded = true, lists = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createList(name)
            eventChannel.send(Event.Created(id))
        }
    }

    fun rename(listId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameList(listId, name) }
    }

    /** Exclui a lista e os itens; tarefas e rotinas donas perdem o vínculo (o diálogo avisa quais). */
    fun delete(listId: Long) {
        viewModelScope.launch { repository.deleteList(listId) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                ShoppingListsViewModel(app.shoppingRepository)
            }
        }
    }
}
