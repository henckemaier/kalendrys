package com.kalendrys.app.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.domain.AppLocale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Rota da tela "Todas as rotinas" (ADR-0002, 14.4). */
const val ALL_ROUTINES_ROUTE = "routines-all"

/**
 * "Todas as rotinas" (v1.8.0, E2; ADR-0002, 14.4): a gestão das rotinas, fora do retrato do dia.
 * Mostra as ativas e as pausadas (as arquivadas não), cada grupo por horário de início, título e id;
 * pausar, retomar e excluir (arquivar) com o Desfazer do app. Sem categorias desde a v1.10.0 (ADR-0002,
 * 16.10.5): nem filtro nem nome da categoria na linha.
 * As regras (alarmes, avisos, catch-up) ficam no [RoutineRepository]; aqui só a lista.
 */
class AllRoutinesViewModel(
    private val routines: RoutineRepository,
    /** Excluir e oferecer o Desfazer do app (NB-E3-C), o mesmo caminho do editor. */
    private val undoOffers: UndoOffers,
) : ViewModel() {

    /** Uma linha: a rotina. */
    data class Row(val routine: Routine) {
        val isPaused: Boolean get() = routine.pausedAt != null
    }

    data class UiState(
        val isLoaded: Boolean = false,
        /** Ativas, por horário. */
        val active: List<Row> = emptyList(),
        /** Pausadas, por horário. */
        val paused: List<Row> = emptyList(),
        /** Existe alguma rotina não arquivada: sem nenhuma, a tela mostra o estado vazio. */
        val hasAny: Boolean = false,
    )

    val uiState: StateFlow<UiState> =
        routines.routines.map { all ->
            val rows = all.filter { it.archivedAt == null }.sortedWith(ORDER).map(::Row)
            UiState(
                isLoaded = true,
                active = rows.filterNot { it.isPaused },
                paused = rows.filter { it.isPaused },
                hasAny = rows.isNotEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun pause(routine: Routine) {
        viewModelScope.launch { routines.pause(routine) }
    }

    fun resume(routine: Routine) {
        viewModelScope.launch { routines.resume(routine) }
    }

    /**
     * "Excluir rotina": o MESMO caminho do editor (NB-E3-C), uma operação do [UndoOffers] no escopo
     * do app (arquiva e oferece); a mensagem com Desfazer é a do app, mostrada nesta tela pelo
     * `KalendrysNavHost`. A tela não tem mais Desfazer próprio.
     */
    fun archive(routine: Routine) {
        undoOffers.deleteRoutine(routine)
    }

    companion object {
        private val ORDER: Comparator<Routine> =
            compareBy<Routine>({ it.startMinute }, { it.title.lowercase(AppLocale.LOCALE) }, { it.id })

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                AllRoutinesViewModel(app.routineRepository, app.undoOffers)
            }
        }
    }
}
