package com.kalendrys.app.ui.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TaskDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: TaskRepository,
) : ViewModel() {

    private val taskId: Long = checkNotNull(savedStateHandle[TASK_ID_ARG])
    private val zone: ZoneId = ZoneId.systemDefault()

    data class UiState(
        val notFound: Boolean = false,
        val task: Task? = null,
        val draft: TaskDraft? = null,
        val history: List<CompletionLog> = emptyList(),
        /**
         * Quando foi feita: hoje, numa tarefa que se repete; a conclusão mais recente, numa que
         * não se repete (feita uma vez, está encerrada). null = pendente.
         */
        val doneAt: Long? = null,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val repeats: Boolean get() = task != null && task.repeat != ReminderRepeat.NONE
        val hasChanges: Boolean get() = task != null && draft != null && draft.differsFrom(task, zone)
        val canSave: Boolean get() = task != null && draft != null && draft.canSaveOver(task, zone)
    }

    sealed interface Event {
        data object Saved : Event
        data class Completed(val logId: Long) : Event
        data object Deleted : Event
    }

    private val draft = MutableStateFlow<TaskDraft?>(null)
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<UiState> =
        combine(repository.observeTask(taskId), repository.observeCompletions(taskId), draft) { task, logs, draft ->
            val today = LocalDate.now(zone)
            val latest = logs.firstOrNull()
            val doneAt = when {
                latest == null -> null
                task != null && task.repeat == ReminderRepeat.NONE -> latest.completedAt
                Instant.ofEpochMilli(latest.completedAt).atZone(zone).toLocalDate() == today -> latest.completedAt
                else -> null
            }
            UiState(
                // Depois de excluir, a tarefa some do banco mas o rascunho segue na tela até ela fechar.
                notFound = task == null && draft == null,
                task = task,
                draft = draft,
                history = logs,
                doneAt = doneAt,
                zone = zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            val task = repository.observeTask(taskId).filterNotNull().first()
            if (draft.value == null) draft.value = TaskDraft.from(task, zone)
        }
    }

    fun updateTitle(title: String) = draft.update { it?.copy(title = title) }

    fun updateNotes(notes: String) = draft.update { it?.copy(notes = notes) }

    fun updateTiming(timing: TaskTiming) = draft.update { it?.copy(timing = timing) }

    fun updateLocation(location: String) = draft.update { it?.copy(location = location) }

    fun save() {
        val task = uiState.value.task ?: return
        val current = draft.value ?: return
        if (!current.canSaveOver(task, zone)) return
        viewModelScope.launch {
            val updated = current.applyTo(task, zone)
            repository.updateTask(updated)
            draft.value = TaskDraft.from(updated, zone)
            eventChannel.send(Event.Saved)
        }
    }

    fun complete() {
        viewModelScope.launch {
            val logId = repository.logCompletion(taskId)
            eventChannel.send(Event.Completed(logId))
        }
    }

    fun undoCompletion(logId: Long) {
        viewModelScope.launch { repository.undoCompletion(logId) }
    }

    fun delete() {
        val task = uiState.value.task ?: return
        viewModelScope.launch {
            repository.deleteTask(task)
            eventChannel.send(Event.Deleted)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                TaskDetailViewModel(createSavedStateHandle(), app.repository)
            }
        }
    }
}
