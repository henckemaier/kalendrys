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
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.domain.ShoppingTotals
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
        /** Itens da lista de compras (só populado quando a rotina já existe e é do tipo COMPRAS). */
        val shoppingItems: List<ShoppingItem> = emptyList(),
        /** Exercícios (só populado quando a rotina já existe e é do tipo EXERCICIO). */
        val exerciseItems: List<ExerciseItem> = emptyList(),
        /** Compras como estavam ao abrir o editor (snapshot); null até a 1ª leitura da lista. */
        val shoppingBaseline: List<ShoppingItem>? = null,
        /** Exercícios como estavam ao abrir o editor (snapshot); null até a 1ª leitura da lista. */
        val exerciseBaseline: List<ExerciseItem>? = null,
    ) {
        /**
         * Compras diferem do snapshot de abertura? Compara ignorando [ShoppingItem.picked]: marcar/
         * desmarcar no carrinho persiste na hora e NÃO conta como "alteração a descartar" (nem arma
         * o diálogo nem reverte); só add/editar/remover é que conta.
         */
        val shoppingItemsChanged: Boolean
            get() = shoppingBaseline?.let { base ->
                shoppingItems.map { it.copy(picked = false) } != base.map { it.copy(picked = false) }
            } ?: false

        /** Exercícios diferem do snapshot de abertura (add/editar/remover/reordenar)? */
        val exerciseItemsChanged: Boolean
            get() = exerciseBaseline?.let { base -> exerciseItems != base } ?: false

        /** Houve edição de item (compras ou exercícios) desde a abertura do editor. */
        val itemsChanged: Boolean get() = shoppingItemsChanged || exerciseItemsChanged

        val hasChanges: Boolean
            get() = when {
                draft == null -> false
                // Agora o descartar/salvar também enxerga as edições de item: o diálogo "Descartar
                // alterações?" aparece e o Salvar habilita quando só os itens mudaram.
                routine != null -> draft.differsFrom(routine) || itemsChanged
                else -> draft != initialDraft || itemsChanged
            }
        val canSave: Boolean get() = draft != null && draft.isValid && (isNew || hasChanges)

        /** Mostra a seção de compras quando a categoria selecionada é COMPRAS. */
        val isShoppingCategory: Boolean get() = selectedKind == RoutineCategoryKind.COMPRAS

        /** Mostra a seção de exercícios quando a categoria selecionada é EXERCICIO. */
        val isExerciseCategory: Boolean get() = selectedKind == RoutineCategoryKind.EXERCICIO

        /** Total da lista, calculado na leitura (soma quantity*priceCents). */
        val shoppingTotalCents: Long get() = ShoppingTotals.totalCents(shoppingItems)
    }

    /** Itens dos módulos de item (compras/exercícios) + seus snapshots de abertura, num ponto só para o combine do estado. */
    private data class ModuleItems(
        val shopping: List<ShoppingItem> = emptyList(),
        val exercise: List<ExerciseItem> = emptyList(),
        val shoppingBaseline: List<ShoppingItem>? = null,
        val exerciseBaseline: List<ExerciseItem>? = null,
    )

    sealed interface Event {
        data object Closed : Event
    }

    private val draft = MutableStateFlow<RoutineDraft?>(null)
    private val initialDraft = MutableStateFlow<RoutineDraft?>(null)
    // Snapshot dos itens como estavam ao abrir o editor (1ª leitura da lista do módulo ativo); serve
    // de base para detectar edição e para restaurar no "Descartar alterações?". Null = ainda não lido.
    private val shoppingBaseline = MutableStateFlow<List<ShoppingItem>?>(null)
    private val exerciseBaseline = MutableStateFlow<List<ExerciseItem>?>(null)
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val routine: Flow<Routine?> = if (isNew) flowOf(null) else repository.observeRoutine(routineId)

    /** Tipo da categoria selecionada no rascunho (reativo), para gatear a lista de compras. */
    private val selectedKind: Flow<RoutineCategoryKind> =
        combine(draft, categoryRepository.categories) { draft, categories -> kindOf(draft, categories) }
            .distinctUntilChanged()

    // Nit de eficiência: só assina shopping_item quando a categoria é COMPRAS; SIMPLE/EXERCICIO não
    // consultam. flatMapLatest troca a assinatura ao mudar o kind. Rotina nova (sem id real) fica vazia.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val shoppingItems: Flow<List<ShoppingItem>> =
        if (isNew) {
            flowOf(emptyList())
        } else {
            selectedKind.flatMapLatest { kind ->
                if (kind == RoutineCategoryKind.COMPRAS) {
                    // Captura o snapshot na 1ª leitura REAL da lista (dentro do ramo COMPRAS, nunca o
                    // emptyList() do gate), para a base refletir o estado de abertura, não o vazio inicial.
                    shoppingRepository.observeItems(routineId)
                        .onEach { if (shoppingBaseline.value == null) shoppingBaseline.value = it }
                } else {
                    flowOf(emptyList())
                }
            }
        }

    // Mesmo gate do shopping: só assina exercise_item quando a categoria é EXERCICIO; os demais kinds
    // não consultam. flatMapLatest troca a assinatura ao mudar o kind. Rotina nova (sem id) fica vazia.
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

    // Combina os dois módulos num ponto só para caber no combine de 5 do estado (só um é não-vazio
    // por vez: o gate por kind garante que compras e exercícios nunca populam ao mesmo tempo).
    private val moduleItems: Flow<ModuleItems> =
        combine(shoppingItems, exerciseItems, shoppingBaseline, exerciseBaseline) { shopping, exercise, sBase, eBase ->
            ModuleItems(shopping, exercise, sBase, eBase)
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
                shoppingItems = module.shopping,
                exerciseItems = module.exercise,
                shoppingBaseline = module.shoppingBaseline,
                exerciseBaseline = module.exerciseBaseline,
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
     * Confirmação do "Descartar alterações?": antes de fechar, desfaz as edições de item (add/editar/
     * remover/reordenar) que já tinham persistido na sessão, restaurando cada lista ao snapshot de
     * abertura. Os campos do rascunho já eram descartados ao fechar sem salvar; agora os itens casam
     * com essa mesma promessa.
     *
     * Marcar/desmarcar compras no carrinho NUNCA é revertido — nem no caso picked-puro (não arma o
     * diálogo), nem quando há edição concomitante: ao restaurar as compras, reaplicamos o [picked]
     * CORRENTE de cada item sobrevivente (casado por id) sobre os demais campos do baseline. Sem
     * isso, o replace traria o picked COMO ESTAVA NA ABERTURA e os toggles da sessão sumiriam junto
     * (S1). Item removido na sessão não tem estado corrente → volta com o picked do baseline.
     * (Exercício não tem picked, então restaura o baseline direto.)
     */
    fun discardChanges() {
        val state = uiState.value
        viewModelScope.launch {
            if (state.shoppingItemsChanged) {
                state.shoppingBaseline?.let { baseline ->
                    val currentPicked = state.shoppingItems.associate { it.id to it.picked }
                    val restored = baseline.map { item ->
                        currentPicked[item.id]?.let { item.copy(picked = it) } ?: item
                    }
                    shoppingRepository.replaceItems(routineId, restored)
                }
            }
            if (state.exerciseItemsChanged) {
                state.exerciseBaseline?.let { exerciseRepository.replaceItems(routineId, it) }
            }
            eventChannel.send(Event.Closed)
        }
    }

    // --- Lista de compras (T30): só vale para uma rotina já salva (routineId real). ---

    fun addShoppingItem(name: String, quantity: Int, priceCents: Long?) {
        if (isNew || name.isBlank()) return
        viewModelScope.launch { shoppingRepository.add(routineId, name, quantity, priceCents) }
    }

    fun updateShoppingItem(item: ShoppingItem, name: String, quantity: Int, priceCents: Long?) {
        if (name.isBlank()) return
        viewModelScope.launch { shoppingRepository.update(item, name, quantity, priceCents) }
    }

    fun toggleShoppingPicked(item: ShoppingItem) {
        viewModelScope.launch { shoppingRepository.setPicked(item, !item.picked) }
    }

    fun removeShoppingItem(item: ShoppingItem) {
        viewModelScope.launch { shoppingRepository.remove(item) }
    }

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

    /** Substitui o rascunho atual — usado para aplicar um modelo numa rotina nova. */
    fun applyDraft(newDraft: RoutineDraft) {
        draft.value = newDraft
    }

    /**
     * Duplica a rotina salva como uma nova ([copyTitle], novo id e data de criação), preservando os
     * demais campos (inclusive os que o editor não mostra). Só vale para uma rotina existente.
     */
    fun duplicate(copyTitle: String) {
        val existing = uiState.value.routine ?: return
        viewModelScope.launch {
            // Uma cópia nasce ativa e não-pausada, mesmo que a original já tenha esses estados.
            repository.add(
                existing.copy(
                    id = NEW_ROUTINE_ID,
                    title = copyTitle,
                    createdAt = System.currentTimeMillis(),
                    archivedAt = null,
                    pausedAt = null,
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
