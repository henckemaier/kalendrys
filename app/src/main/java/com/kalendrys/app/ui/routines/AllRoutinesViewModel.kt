package com.kalendrys.app.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.domain.AppLocale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Rota da tela "Todas as rotinas" (ADR-0002, 14.4). */
const val ALL_ROUTINES_ROUTE = "routines-all"

/**
 * "Todas as rotinas" (v1.8.0, E2; ADR-0002, 14.4): a gestão das rotinas, fora do retrato do dia.
 * Mostra as ativas e as pausadas (as arquivadas não), cada grupo por horário de início, título e id;
 * pausar, retomar e arquivar com Desfazer. O filtro por categoria (P3) mora aqui, não na Hoje.
 * As regras (alarmes, avisos, catch-up) ficam no [RoutineRepository]; aqui só a lista e o filtro.
 */
class AllRoutinesViewModel(
    private val routines: RoutineRepository,
    categories: RoutineCategoryRepository,
) : ViewModel() {

    /** Uma linha: a rotina e a categoria dela (null sem categoria ou com categoria excluída). */
    data class Row(val routine: Routine, val category: RoutineCategory?) {
        val isPaused: Boolean get() = routine.pausedAt != null
    }

    data class UiState(
        val isLoaded: Boolean = false,
        /** Ativas do filtro atual, por horário. */
        val active: List<Row> = emptyList(),
        /** Pausadas do filtro atual, por horário. */
        val paused: List<Row> = emptyList(),
        /** Existe alguma rotina não arquivada (sem filtro): sem nenhuma, a tela mostra o estado vazio. */
        val hasAny: Boolean = false,
        /** Categorias existentes, para os chips (vazio = a tela não mostra o filtro). */
        val categories: List<RoutineCategory> = emptyList(),
        /** Categoria do filtro ativo, ou null para "todas". */
        val selectedCategoryId: Long? = null,
    )

    /** Rotina arquivada agora, para a tela oferecer Desfazer. */
    data class Archived(val routineId: Long)

    private val selectedCategoryId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<UiState> =
        combine(routines.routines, categories.categories, selectedCategoryId) { all, categoryList, rawSelected ->
            // Categoria do filtro excluída: volta sozinho para "todas".
            val selected = rawSelected?.takeIf { id -> categoryList.any { it.id == id } }
            val byId = categoryList.associateBy { it.id }
            val visible = all.filter { it.archivedAt == null }
            val rows = visible
                .filter { selected == null || it.categoryId == selected }
                .sortedWith(ORDER)
                .map { Row(it, it.categoryId?.let(byId::get)) }
            UiState(
                isLoaded = true,
                active = rows.filterNot { it.isPaused },
                paused = rows.filter { it.isPaused },
                hasAny = visible.isNotEmpty(),
                categories = categoryList,
                selectedCategoryId = selected,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val archivedEvents = Channel<Archived>(Channel.BUFFERED)
    val archived: Flow<Archived> = archivedEvents.receiveAsFlow()

    fun selectCategory(categoryId: Long?) {
        selectedCategoryId.value = categoryId
    }

    fun pause(routine: Routine) {
        viewModelScope.launch { routines.pause(routine) }
    }

    fun resume(routine: Routine) {
        viewModelScope.launch { routines.resume(routine) }
    }

    fun archive(routine: Routine) {
        viewModelScope.launch {
            routines.archive(routine)
            archivedEvents.send(Archived(routine.id))
        }
    }

    fun undoArchive(routineId: Long) {
        viewModelScope.launch { routines.unarchive(routineId) }
    }

    companion object {
        private val ORDER: Comparator<Routine> =
            compareBy<Routine>({ it.startMinute }, { it.title.lowercase(AppLocale.LOCALE) }, { it.id })

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                AllRoutinesViewModel(app.routineRepository, app.routineCategoryRepository)
            }
        }
    }
}
