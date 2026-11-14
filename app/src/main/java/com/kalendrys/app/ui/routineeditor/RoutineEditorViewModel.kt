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
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.tasks.ReminderTime
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoutineEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutineRepository,
    private val categoryRepository: RoutineCategoryRepository,
    private val shoppingRepository: ShoppingRepository,
    private val exerciseRepository: ExerciseRepository,
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
        /**
         * Tipo da categoria selecionada no rascunho. ÚNICO ponto de fallback: categoria desconhecida
         * ou sem categoria → [RoutineCategoryKind.SIMPLE] (via [RoutineCategoryKind.fromKey]).
         */
        val selectedKind: RoutineCategoryKind = RoutineCategoryKind.SIMPLE,
        /** Exercícios (só populado quando a rotina já existe e é do tipo EXERCICIO). */
        val exerciseItems: List<ExerciseItem> = emptyList(),
        /** Exercícios como estavam ao abrir o editor (snapshot); null até a 1ª leitura da lista. */
        val exerciseBaseline: List<ExerciseItem>? = null,
        /**
         * Resumo da lista de compras anexada ao RASCUNHO ([RoutineDraft.shoppingListId]); null sem
         * lista ou com vínculo órfão (lista inexistente equivale a "sem lista", ADR-0001 4.1).
         */
        val shoppingList: ShoppingListSummary? = null,
        /** Todas as listas, para o "Usar lista existente" do seletor. */
        val availableLists: List<ShoppingListSummary> = emptyList(),
    ) {
        /** Exercícios diferem do snapshot de abertura (add/editar/remover/reordenar)? */
        val exerciseItemsChanged: Boolean
            get() = exerciseBaseline?.let { base -> exerciseItems != base } ?: false

        val hasChanges: Boolean
            get() = when {
                draft == null -> false
                // O vínculo da lista está no rascunho (conta no differsFrom); os itens da lista não
                // contam (P7). Exercícios seguem com o snapshot.
                routine != null -> draft.differsFrom(routine) || exerciseItemsChanged
                else -> draft != initialDraft || exerciseItemsChanged
            }
        val canSave: Boolean get() = draft != null && draft.isValid && (isNew || hasChanges)

        /** Mostra a seção de exercícios quando a categoria selecionada é EXERCICIO. */
        val isExerciseCategory: Boolean get() = selectedKind == RoutineCategoryKind.EXERCICIO
    }

    /** Exercícios + snapshot e a lista anexada + listas disponíveis, num ponto só para o combine do estado. */
    private data class ModuleItems(
        val exercise: List<ExerciseItem> = emptyList(),
        val exerciseBaseline: List<ExerciseItem>? = null,
        val shoppingList: ShoppingListSummary? = null,
        val availableLists: List<ShoppingListSummary> = emptyList(),
    )

    sealed interface Event {
        data object Closed : Event
    }

    private val draft = MutableStateFlow<RoutineDraft?>(null)
    private val initialDraft = MutableStateFlow<RoutineDraft?>(null)
    // Snapshot dos exercícios como estavam ao abrir o editor (1ª leitura da lista); serve de base para
    // detectar edição e para restaurar no "Descartar alterações?". Null = ainda não lido.
    private val exerciseBaseline = MutableStateFlow<List<ExerciseItem>?>(null)
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val routine: Flow<Routine?> = if (isNew) flowOf(null) else repository.observeRoutine(routineId)

    /** Tipo da categoria selecionada no rascunho (reativo), para gatear a lista de exercícios. */
    private val selectedKind: Flow<RoutineCategoryKind> =
        combine(draft, categoryRepository.categories) { draft, categories -> kindOf(draft, categories) }
            .distinctUntilChanged()

    // Lista anexada ao RASCUNHO, para qualquer categoria e inclusive em rotina nova (P6): segue o
    // vínculo do draft, não o do banco, para refletir anexar/desvincular antes de salvar.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val shoppingList: Flow<ShoppingListSummary?> =
        draft.map { it?.shoppingListId }
            .distinctUntilChanged()
            .flatMapLatest { listId -> if (listId == null) flowOf(null) else shoppingRepository.observeSummary(listId) }

    // Só assina exercise_item quando a categoria é EXERCICIO; os demais kinds não consultam.
    // flatMapLatest troca a assinatura ao mudar o kind. Rotina nova (sem id) fica vazia.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val exerciseItems: Flow<List<ExerciseItem>> =
        if (isNew) {
            flowOf(emptyList())
        } else {
            selectedKind.flatMapLatest { kind ->
                if (kind == RoutineCategoryKind.EXERCICIO) {
                    exerciseRepository.observeItems(routineId)
                        .onEach { if (exerciseBaseline.value == null) exerciseBaseline.value = it }
                } else {
                    flowOf(emptyList())
                }
            }
        }

    // Combina os módulos num ponto só para caber no combine de 5 do estado.
    private val moduleItems: Flow<ModuleItems> =
        combine(
            exerciseItems,
            exerciseBaseline,
            shoppingList,
            shoppingRepository.observeSummaries(),
        ) { exercise, eBase, list, lists ->
            ModuleItems(exercise, eBase, list, lists)
        }

    val uiState: StateFlow<UiState> =
        combine(
            routine,
            draft,
            initialDraft,
            categoryRepository.categories,
            moduleItems,
        ) { routine, draft, initial, categories, module ->
            UiState(
                isNew = isNew,
                // Depois de excluir, a rotina continua no banco (arquivada) mas o editor já fechou.
                notFound = !isNew && routine == null && draft == null,
                routine = routine,
                draft = draft,
                initialDraft = initial,
                categories = categories,
                selectedKind = kindOf(draft, categories),
                exerciseItems = module.exercise,
                exerciseBaseline = module.exerciseBaseline,
                shoppingList = module.shoppingList,
                availableLists = module.availableLists,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(isNew = isNew))

    /**
     * Resolve o tipo da categoria selecionada no rascunho. ÚNICO ponto de fallback do kind: sem
     * categoria ou kind desconhecido → [RoutineCategoryKind.SIMPLE] (via [RoutineCategoryKind.fromKey]).
     */
    private fun kindOf(draft: RoutineDraft?, categories: List<RoutineCategory>): RoutineCategoryKind =
        draft?.categoryId
            ?.let { id -> categories.firstOrNull { it.id == id } }
            ?.let { RoutineCategoryKind.fromKey(it.kind) }
            ?: RoutineCategoryKind.SIMPLE

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

    fun updateWeekInterval(weeks: Int) =
        draft.update { it?.copy(weekInterval = weeks.coerceIn(1, RoutineDraft.MAX_WEEK_INTERVAL)) }

    fun updateStartDate(epochDay: Long?) = draft.update { it?.copy(startEpochDay = epochDay) }

    fun updateEndDate(epochDay: Long?) = draft.update { it?.copy(endEpochDay = epochDay) }

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

    /**
     * Confirmação do "Descartar alterações?": antes de fechar, desfaz as edições de exercício (add/
     * editar/remover/reordenar) que já tinham persistido na sessão, restaurando o snapshot de
     * abertura. Os campos do rascunho (inclusive o vínculo da lista de compras) são descartados só
     * por fechar sem salvar.
     *
     * Itens de compras NÃO são revertidos (ADR-0001, P7): são da lista e persistem na hora. Uma
     * lista criada nesta sessão e descartada continua existindo como lista avulsa.
     */
    fun discardChanges() {
        val state = uiState.value
        viewModelScope.launch {
            if (state.exerciseItemsChanged) {
                state.exerciseBaseline?.let { exerciseRepository.replaceItems(routineId, it) }
            }
            eventChannel.send(Event.Closed)
        }
    }

    // --- Lista de compras (ADR-0001): o vínculo vive no rascunho; vale para qualquer rotina. ---

    /** "Usar lista existente": anexa [listId] ao rascunho (grava só ao salvar). */
    fun attachShoppingList(listId: Long) = draft.update { it?.copy(shoppingListId = listId) }

    /**
     * "Criar nova": cria a lista na hora (persistida, avulsa até a rotina ser salva) e a anexa ao
     * rascunho. Se o editor for descartado, a lista fica na aba Listas como avulsa (P7).
     */
    fun createShoppingList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val listId = shoppingRepository.createList(name)
            draft.update { it?.copy(shoppingListId = listId) }
        }
    }

    /** "Desvincular": tira a lista do rascunho; a lista e os itens continuam na aba Listas. */
    fun detachShoppingList() = draft.update { it?.copy(shoppingListId = null) }

    // --- Lista de exercícios (T34): só vale para uma rotina já salva (routineId real). ---

    fun addExerciseItem(name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        if (isNew || name.isBlank()) return
        viewModelScope.launch { exerciseRepository.add(routineId, name, sets, reps, load, restSeconds) }
    }

    fun updateExerciseItem(item: ExerciseItem, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        if (name.isBlank()) return
        viewModelScope.launch { exerciseRepository.update(item, name, sets, reps, load, restSeconds) }
    }

    fun removeExerciseItem(item: ExerciseItem) {
        viewModelScope.launch { exerciseRepository.remove(item) }
    }

    /** Reordena o exercício para cima ([up] = true) ou para baixo, trocando a posição com o vizinho. */
    fun moveExerciseItem(item: ExerciseItem, up: Boolean) {
        viewModelScope.launch { exerciseRepository.move(item, up) }
    }

    /**
     * Substitui o rascunho atual — usado para aplicar um modelo numa rotina nova. O modelo não sabe
     * de listas, então a lista já anexada ao rascunho é mantida.
     */
    fun applyDraft(newDraft: RoutineDraft) {
        draft.update { current -> newDraft.copy(shoppingListId = current?.shoppingListId ?: newDraft.shoppingListId) }
    }

    /**
     * Duplica a rotina salva como uma nova ([copyTitle], novo id e data de criação), preservando os
     * demais campos (inclusive os que o editor não mostra). Só vale para uma rotina existente.
     */
    fun duplicate(copyTitle: String) {
        val existing = uiState.value.routine ?: return
        viewModelScope.launch {
            // Uma cópia nasce ativa e não-pausada, mesmo que a original já tenha esses estados. Não
            // leva a lista de compras (ADR-0001 4.3): a cópia começa sem vínculo.
            repository.add(
                existing.copy(
                    id = NEW_ROUTINE_ID,
                    title = copyTitle,
                    createdAt = System.currentTimeMillis(),
                    archivedAt = null,
                    pausedAt = null,
                    shoppingListId = null,
                ),
            )
            eventChannel.send(Event.Closed)
        }
    }

    /**
     * Pausa a rotina (para de agendar/avisar, mas mantém config + histórico) ou a retoma, conforme
     * o estado atual. Não fecha o editor: o botão reflete o novo estado quando a rotina reemite.
     */
    fun togglePause() {
        val existing = uiState.value.routine ?: return
        viewModelScope.launch {
            if (existing.pausedAt == null) repository.pause(existing) else repository.resume(existing)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutineEditorViewModel(
                    createSavedStateHandle(),
                    app.routineRepository,
                    app.routineCategoryRepository,
                    app.shoppingRepository,
                    app.exerciseRepository,
                )
            }
        }
    }
}
