package com.kalendrys.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.DailySummarySchedule
import com.kalendrys.app.domain.NextAppAlarm
import com.kalendrys.app.domain.NextAppAlarms
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Próximo aviso do Kalendrys para o diagnóstico de alarmes (v1.7.1, E3): calculado dos dados com as
 * mesmas funções que os agendadores usam (lembrete de tarefa, início de rotina que avisa e resumo
 * diário), não lido do AlarmManager, que mostraria o despertador de qualquer app.
 *
 * Sob demanda: [refresh] na retomada da tela (ON_RESUME), sem observar o banco. Os adiamentos
 * ("Adiar") não entram: não ficam gravados nos dados (limitação aceita, ADR-0002, 13.3).
 */
class AlarmDiagnosticsViewModel(
    private val tasks: TaskRepository,
    private val routines: RoutineRepository,
    private val settings: SettingsRepository,
    /** O relógio do app (v1.10.0, 16.3). */
    private val clock: AppClock,
) : ViewModel() {

    sealed interface NextAlarmState {
        data object Loading : NextAlarmState

        /** [next] null: nada agendado. */
        data class Loaded(val next: NextAppAlarm?) : NextAlarmState
    }

    private val _nextAlarm = MutableStateFlow<NextAlarmState>(NextAlarmState.Loading)
    val nextAlarm: StateFlow<NextAlarmState> = _nextAlarm.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _nextAlarm.value = NextAlarmState.Loaded(compute(clock.nowMillis())) }
    }

    private suspend fun compute(now: Long): NextAppAlarm? {
        val zone = clock.zone()
        val task = tasks.nextReminderToDeliver(now, zone)
        val routine = routines.nextRemindingStart(now)
        val current = settings.settings.first()
        val summary = DailySummarySchedule.nextAt(
            current.summaryEnabled,
            current.summaryTime,
            LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone),
            zone,
        )
        return NextAppAlarms.earliest(task = task, routine = routine, summary = summary)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                AlarmDiagnosticsViewModel(app.repository, app.routineRepository, app.settingsRepository, app.clock)
            }
        }
    }
}
