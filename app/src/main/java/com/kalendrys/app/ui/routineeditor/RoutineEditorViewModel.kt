package com.kalendrys.app.ui.routineeditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.tasks.ReminderTime
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoutineEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutineRepository,
    private val categoryRepository: RoutineCategoryRepository,
) : ViewModel() {

    private val routineId: Long = savedStateHandle[ROUTINE_ID_ARG] ?: NEW_ROUTINE_ID
    val isNew: Boolean = routineId == NEW_ROUTINE_ID

    data class UiState(
        val isNew: Boolean = true,
        val notFound: Boolean = false,
        val routine: Routine? = null,
        val draft: RoutineDraft? = null,
        /** Como o rascunho começou; para uma rotina nova, mudar algo já pede confirmação ao sair. */
        val initialDraft: RoutineDraft? = null,
        /** Categorias disponíveis para atribuir à rotina. */
        val categories: List<RoutineCategory> = emptyList(),
    ) {
        val hasChanges: Boolean
            get() = when {
                draft == null -> false
                routine != null -> draft.differsFrom(routine)
                else -> draft != initialDraft
            }
        val canSave: Boolean get() = draft != null && draft.isValid && (isNew || hasChanges)
    }

    sealed interface Event {
        data object Closed : Event
    }

    private val draft = MutableStateFlow<RoutineDraft?>(null)
    private val initialDraft = MutableStateFlow<RoutineDraft?>(null)
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val routine: Flow<Routine?> = if (isNew) flowOf(null) else repository.observeRoutine(routineId)

    val uiState: StateFlow<UiState> =
        combine(routine, draft, initialDraft, categoryRepository.categories) { routine, draft, initial, categories ->
            UiState(
                isNew = isNew,
                // Depois de excluir, a rotina continua no banco (arquivada) mas o editor já fechou.
                notFound = !isNew && routine == null && draft == null,
                routine = routine,
                draft = draft,
                initialDraft = initial,
                categories = categories,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(isNew = isNew))

    init {
        viewModelScope.launch {
            if (draft.value != null) return@launch
            val start = if (isNew) {
                val now = LocalDateTime.now()
                val suggested = ReminderTime.suggestedTime(now.toLocalDate(), now)
                RoutineDraft(startMinute = suggested.hour * 60 + suggested.minute)
            } else {
                RoutineDraft.from(repository.observeRoutine(routineId).filterNotNull().first())
            }
            initialDraft.value = start
            draft.value = start
        }
    }

    fun updateTitle(title: String) = draft.update { it?.copy(title = title) }

    fun updateNotes(notes: String) = draft.update { it?.copy(notes = notes) }

    fun updateIcon(icon: RoutineIcon) = draft.update { it?.copy(icon = icon) }

    fun updateColor(color: RoutineColor) = draft.update { it?.copy(color = color) }

    fun updateStart(time: LocalTime) = draft.update { it?.copy(startMinute = time.hour * 60 + time.minute) }

    fun updateDuration(minutes: Int) = draft.update { it?.copy(durationMinutes = minutes) }

    fun updateEnd(time: LocalTime) = draft.update { it?.withEnd(time.hour * 60 + time.minute) }

    fun toggleDay(day: DayOfWeek) = draft.update { it?.copy(daysOfWeek = RoutineDays.toggle(it.daysOfWeek, day)) }

    fun setDays(mask: Int) = draft.update { it?.copy(daysOfWeek = mask) }

    fun updateRemind(remind: Boolean) = draft.update { it?.copy(remind = remind) }

    fun updateCategory(categoryId: Long?) = draft.update { it?.copy(categoryId = categoryId) }

    fun save() {
        val state = uiState.value
        val current = state.draft ?: return
        if (!state.canSave) return
        viewModelScope.launch {
            val existing = state.routine
            if (existing == null) repository.add(current.toNewRoutine()) else repository.update(current.applyTo(existing))
            eventChannel.send(Event.Closed)
        }
    }

    fun delete() {
        val existing = uiState.value.routine ?: return
        viewModelScope.launch {
            repository.archive(existing)
            eventChannel.send(Event.Closed)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutineEditorViewModel(createSavedStateHandle(), app.routineRepository, app.routineCategoryRepository)
            }
        }
    }
}
