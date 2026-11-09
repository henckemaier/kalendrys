package com.kalendrys.app.ui.routineexception

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
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.domain.RoutineOverrideType
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Exceção pontual de uma ocorrência (pular/remarcar), sem desligar a rotina. Lê o estado atual do
 * dia e escreve a exceção pelo [RoutineRepository] (que honra o contrato do RoutineOverrideDao).
 */
class RoutineExceptionViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutineRepository,
) : ViewModel() {

    private val routineId: Long = savedStateHandle[ROUTINE_EXCEPTION_ROUTINE_ID_ARG] ?: 0L
    private val epochDay: Long = savedStateHandle[ROUTINE_EXCEPTION_DAY_ARG] ?: 0L

    data class UiState(
        val loading: Boolean = true,
        val routine: Routine? = null,
        val date: LocalDate = LocalDate.ofEpochDay(0),
        /** Tipo da exceção atual: `null` (sem exceção), SKIP ou RESCHEDULE. */
        val currentType: String? = null,
        /** Minuto para inicializar o seletor de horário: o remarcado, ou o da rotina. */
        val startMinute: Int = 0,
    ) {
        val hasException: Boolean get() = currentType != null
    }

    sealed interface Event {
        data object Closed : Event
    }

    private val _uiState = MutableStateFlow(UiState(date = LocalDate.ofEpochDay(epochDay)))
    val uiState: StateFlow<UiState> = _uiState

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val routine = repository.observeRoutine(routineId).first()
            val override = repository.occurrenceOverride(routineId, epochDay)
            val rescheduledMinute = override
                ?.takeIf { it.type == RoutineOverrideType.RESCHEDULE }
                ?.newStartMinute
            _uiState.value = UiState(
                loading = false,
                routine = routine,
                date = LocalDate.ofEpochDay(epochDay),
                currentType = override?.type,
                startMinute = rescheduledMinute ?: routine?.startMinute ?: 0,
            )
        }
    }

    fun skip() = applyAndClose { repository.skipOccurrence(routineId, epochDay) }

    fun rescheduleTime(minute: Int) =
        applyAndClose { repository.rescheduleOccurrence(routineId, epochDay, newStartMinute = minute, newEpochDay = null) }

    fun moveToDay(targetEpochDay: Long) =
        applyAndClose { repository.rescheduleOccurrence(routineId, epochDay, newStartMinute = null, newEpochDay = targetEpochDay) }

    fun clearException() = applyAndClose { repository.clearOccurrenceException(routineId, epochDay) }

    private fun applyAndClose(action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            eventChannel.send(Event.Closed)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutineExceptionViewModel(createSavedStateHandle(), app.routineRepository)
            }
        }
    }
}
