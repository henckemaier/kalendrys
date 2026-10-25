package com.kalendrys.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {

    data class UiState(
        val pending: List<Task> = emptyList(),
        val doneToday: List<Task> = emptyList(),
        val isLoaded: Boolean = false,
    ) {
        val isEmpty: Boolean get() = pending.isEmpty() && doneToday.isEmpty()
    }

    /** Ações que a tela oferece desfazer pelo snackbar. */
    sealed interface UndoableAction {
        data class Completed(val logId: Long) : UndoableAction
        data class Deleted(val task: Task) : UndoableAction
    }

    val uiState: StateFlow<UiState> =
        combine(repository.tasks, repository.completionsToday) { tasks, logsToday ->
            val doneTodayIds = logsToday.mapTo(HashSet()) { it.taskId }
            val (done, pending) = tasks.partition { it.id in doneTodayIds }
            UiState(pending = pending, doneToday = done, isLoaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val undoableActions = Channel<UndoableAction>(Channel.BUFFERED)
    val undoable: Flow<UndoableAction> = undoableActions.receiveAsFlow()

    fun addTask(title: String, notes: String, reminderAt: Long?, repeat: ReminderRepeat) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.addTask(title, notes, reminderAt, repeat) }
    }

    fun complete(task: Task) {
        viewModelScope.launch {
            val logId = repository.logCompletion(task.id)
            undoableActions.send(UndoableAction.Completed(logId))
        }
    }

    fun delete(task: Task) {
        viewModelScope.launch {
            repository.deleteTask(task)
            undoableActions.send(UndoableAction.Deleted(task))
        }
    }

    fun undo(action: UndoableAction) {
        viewModelScope.launch {
            when (action) {
                is UndoableAction.Completed -> repository.undoCompletion(action.logId)
                is UndoableAction.Deleted -> repository.restoreTask(action.task)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                TaskViewModel(app.repository)
            }
        }
    }
}
