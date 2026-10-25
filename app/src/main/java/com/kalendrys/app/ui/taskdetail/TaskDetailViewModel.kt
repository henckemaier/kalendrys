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

    data class UiState(
        val notFound: Boolean = false,
        val task: Task? = null,
        val draft: TaskDraft? = null,
        val history: List<CompletionLog> = emptyList(),
        val doneTodayAt: Long? = null,
    ) {
        val hasChanges: Boolean get() = task != null && draft != null && draft.differsFrom(task)
        val canSave: Boolean
            get() = task != null && draft != null && draft.canSaveOver(task, System.currentTimeMillis())
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
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            UiState(
                // Depois de excluir, a tarefa some do banco mas o rascunho segue na tela até ela fechar.
                notFound = task == null && draft == null,
                task = task,
                draft = draft,
                history = logs,
                doneTodayAt = logs.firstOrNull {
                    Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == today
                }?.completedAt,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            val task = repository.observeTask(taskId).filterNotNull().first()
            if (draft.value == null) draft.value = TaskDraft.from(task)
        }
    }

    fun updateTitle(title: String) = draft.update { it?.copy(title = title) }

    fun updateNotes(notes: String) = draft.update { it?.copy(notes = notes) }

    fun updateReminder(reminderAt: Long?) = draft.update { it?.copy(reminderAt = reminderAt) }

    fun updateRepeat(repeat: ReminderRepeat) = draft.update { it?.copy(reminderRepeat = repeat) }

    fun save() {
        val task = uiState.value.task ?: return
        val current = draft.value ?: return
        if (!current.canSaveOver(task, System.currentTimeMillis())) return
        viewModelScope.launch {
            val updated = current.applyTo(task)
            repository.updateTask(updated)
            draft.value = TaskDraft.from(updated)
            eventChannel.send(Event.Saved)
        }
    }

    fun completeToday() {
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
