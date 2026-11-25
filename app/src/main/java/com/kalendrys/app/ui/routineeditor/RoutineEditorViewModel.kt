package com.kalendrys.app.ui.routineeditor

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.entity.ExerciseItem
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.nowLocal
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.ui.edit.EditQueue
import com.kalendrys.app.ui.edit.TextFieldBuffer
import com.kalendrys.app.ui.tasks.ReminderTime
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor de rotina (v1.9.0, ADR-0002 15.5), em dois modos:
 *
 * - **Criação** ([isNew]): rascunho ([RoutineDraft]) e o botão "Criar rotina". Nada é gravado nem
 *   agendado antes dele; a lista nova escolhida aqui é criada JUNTO com a rotina, numa transação
 *   ([RoutineRepository.add] com `newListName`). Depois de criar, a tela fecha (P3). Voltar com algo
 *   preenchido pede confirmação ([UiState.hasDraftChanges]).
 * - **Edição**: a tela mostra a linha do banco e envia MUDANÇAS ([RoutineChange]) pelo
 *   [RoutineRepository.edit], que as aplica sobre a linha mais recente (15.2): pausar, arquivar ou
 *   excluir a lista por fora não são desfeitos. Campos discretos gravam na hora; título e
 *   observações passam por um [TextFieldBuffer] (500 ms e sempre ao sair, 15.3).
 *
 * Gravações da edição (campos e exercícios) vão por uma FILA única ([EditQueue]), na ordem em que a
 * pessoa mexeu, no escopo do app ([editScope]): cada mudança é calculada sobre a linha lida na hora de gravar, então dois toques
 * seguidos em dias não se anulam. A fila fecha no onCleared ([finish]), depois de gravar o texto
 * pendente.
 */
class RoutineEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutineRepository,
    private val categoryRepository: RoutineCategoryRepository,
    private val shoppingRepository: ShoppingRepository,
    private val exerciseRepository: ExerciseRepository,
    /** Oferta de Desfazer do app (P8): "Excluir rotina" oferece aqui e a mensagem aparece na origem. */
    private val undoOffers: UndoOffers,
    /** Escopo das gravações ([KalendrysApp.editScope]): sobrevive ao fechar a tela. */
    private val editScope: CoroutineScope,
    /** O relógio do app (v1.10.0, 16.3). */
    private val clock: AppClock,
) : ViewModel() {

    private val routineId: Long = savedStateHandle[ROUTINE_ID_ARG] ?: NEW_ROUTINE_ID
    val isNew: Boolean = routineId == NEW_ROUTINE_ID

    /** Campos do editor, para o Reverter saber o que foi mexido na sessão (N4 do Esquadro). */
    enum class Field { TITLE, NOTES, START, DURATION, DAYS, WEEK_INTERVAL, VALIDITY, REMIND, CATEGORY, COLOR, ICON, LIST }

    data class UiState(
        val isNew: Boolean = true,
        val notFound: Boolean = false,
        /** A linha do banco (edição); null na criação e até a 1ª leitura. */
        val routine: Routine? = null,
        /**
         * O que a tela mostra: o rascunho (criação) ou a linha do banco com o texto dos campos em
         * digitação (edição). Na edição é só leitura: nunca é gravado inteiro.
         */
        val fields: RoutineDraft? = null,
        /** Criação: como o rascunho começou, para pedir confirmação ao voltar. */
        val initialDraft: RoutineDraft? = null,
        /** O título no campo está vazio (não é gravado; ao sair volta ao último válido). */
        val titleError: Boolean = false,
        val categories: List<RoutineCategory> = emptyList(),
        /**
         * Tipo da categoria selecionada. ÚNICO ponto de fallback: categoria desconhecida ou sem
         * categoria → [RoutineCategoryKind.SIMPLE] (via [RoutineCategoryKind.fromKey]).
         */
        val selectedKind: RoutineCategoryKind = RoutineCategoryKind.SIMPLE,
        /** Exercícios (só populado quando a rotina já existe e é do tipo EXERCICIO). */
        val exerciseItems: List<ExerciseItem> = emptyList(),
        /** Exercícios como estavam ao abrir o editor; null até a 1ª leitura da lista. */
        val exerciseBaseline: List<ExerciseItem>? = null,
        /** Resumo da lista anexada; null sem lista ou com vínculo órfão (ADR-0001 4.1). */
        val shoppingList: ShoppingListSummary? = null,
        /** Todas as listas, para o "Usar lista existente" do seletor. */
        val availableLists: List<ShoppingListSummary> = emptyList(),
        /** Criação: nome da lista NOVA que será criada junto com a rotina. */
        val pendingListName: String? = null,
        /** Edição: campos mexidos na sessão (o Reverter só manda esses de volta). */
        val touched: Set<Field> = emptySet(),
        /** Criação: o "Criar rotina" já foi tocado e a gravação está em curso (botão e descarte desligados). */
        val creating: Boolean = false,
    ) {
        val exerciseItemsChanged: Boolean
            get() = exerciseBaseline?.let { base -> exerciseItems != base } ?: false

        /** Criação: algo mudou desde a abertura (voltar pede confirmação). */
        val hasDraftChanges: Boolean
            get() = isNew && fields != null && (fields != initialDraft || pendingListName != null)

        /** Criação: o "Criar rotina" está liberado. */
        val canCreate: Boolean get() = isNew && fields?.isValid == true

        /** Edição: há o que reverter. */
        val canRevert: Boolean get() = !isNew && (touched.isNotEmpty() || exerciseItemsChanged)

        val isExerciseCategory: Boolean get() = selectedKind == RoutineCategoryKind.EXERCICIO
    }

    sealed interface Event {
        data object Closed : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    // --- Criação ------------------------------------------------------------------------------------

    private val draft = MutableStateFlow<RoutineDraft?>(null)
    private val initialDraft = MutableStateFlow<RoutineDraft?>(null)
    private val pendingListName = MutableStateFlow<String?>(null)
    private val creating = MutableStateFlow(false)

    /** Excluir ou duplicar já foi pedido (a tela vai fechar): um segundo toque não repete (OBS-2). */
    private var leaving = false

    // --- Edição -------------------------------------------------------------------------------------

    private val routine: Flow<Routine?> = if (isNew) flowOf(null) else repository.observeRoutine(routineId)
    private val loaded = MutableStateFlow(isNew)

    /** Retrato dos campos na abertura (1ª leitura), para o Reverter. */
    private var snapshot: RoutineDraft? = null
    private val touched = MutableStateFlow<Set<Field>>(emptySet())

    /** Exercícios como estavam ao abrir o editor; null = ainda não lido. */
    private val exerciseBaseline = MutableStateFlow<List<ExerciseItem>?>(null)

    /** A fila única das gravações da edição (peça comum, [EditQueue]), na ordem do toque. */
    private val writes = EditQueue(editScope) { repository.observeRoutine(routineId).first() }

    private val titleBuffer = TextFieldBuffer(
        timerScope = viewModelScope,
        writeScope = editScope,
        isValid = { it.isNotBlank() },
    ) { value -> enqueue { RoutineChange.Title(value) } }

    private val notesBuffer = TextFieldBuffer(timerScope = viewModelScope, writeScope = editScope) { value ->
        enqueue { RoutineChange.Notes(value) }
    }

    /** O que a tela mostra: rascunho (criação) ou a linha com o texto em digitação (edição). */
    private val fields: Flow<RoutineDraft?> =
        if (isNew) {
            draft
        } else {
            combine(routine, titleBuffer.text, notesBuffer.text) { row, title, notes ->
                row?.let { RoutineDraft.from(it).copy(title = title ?: it.title, notes = notes ?: it.notes) }
            }
        }

    private val selectedKind: Flow<RoutineCategoryKind> =
        combine(fields, categoryRepository.categories) { f, categories -> kindOf(f, categories) }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val shoppingList: Flow<ShoppingListSummary?> =
        fields.map { it?.shoppingListId }
            .distinctUntilChanged()
            .flatMapLatest { listId -> if (listId == null) flowOf(null) else shoppingRepository.observeSummary(listId) }

    // Só assina exercise_item quando a categoria é EXERCICIO; rotina nova (sem id) fica vazia.
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

    private data class ModuleItems(
        val exercise: List<ExerciseItem> = emptyList(),
        val exerciseBaseline: List<ExerciseItem>? = null,
        val shoppingList: ShoppingListSummary? = null,
        val availableLists: List<ShoppingListSummary> = emptyList(),
    )

    private val moduleItems: Flow<ModuleItems> =
        combine(exerciseItems, exerciseBaseline, shoppingList, shoppingRepository.observeSummaries()) { e, base, list, lists ->
            ModuleItems(e, base, list, lists)
        }

    private data class EditorState(
        val routine: Routine?,
        val loaded: Boolean,
        val fields: RoutineDraft?,
        val initial: RoutineDraft?,
        val pendingListName: String?,
        val touched: Set<Field>,
        val creating: Boolean,
    )

    private val editorState: Flow<EditorState> =
        combine(
            combine(routine, loaded, fields) { r, l, f -> Triple(r, l, f) },
            initialDraft,
            pendingListName,
            touched,
            creating,
        ) { (r, l, f), initial, pending, t, c -> EditorState(r, l, f, initial, pending, t, c) }

    val uiState: StateFlow<UiState> =
        combine(editorState, categoryRepository.categories, moduleItems) { editor, categories, module ->
            UiState(
                isNew = isNew,
                notFound = !isNew && editor.loaded && editor.routine == null,
                routine = editor.routine,
                fields = editor.fields,
                initialDraft = editor.initial,
                titleError = !isNew && editor.fields != null && editor.fields.title.isBlank(),
                categories = categories,
                selectedKind = kindOf(editor.fields, categories),
                exerciseItems = module.exercise,
                exerciseBaseline = module.exerciseBaseline,
                shoppingList = module.shoppingList,
                availableLists = module.availableLists,
                pendingListName = editor.pendingListName,
                touched = editor.touched,
                creating = editor.creating,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(isNew = isNew))

    private fun kindOf(fields: RoutineDraft?, categories: List<RoutineCategory>): RoutineCategoryKind =
        fields?.categoryId
            ?.let { id -> categories.firstOrNull { it.id == id } }
            ?.let { RoutineCategoryKind.fromKey(it.kind) }
            ?: RoutineCategoryKind.SIMPLE

    init {
        if (isNew) {
            val now = clock.nowLocal()
            val suggested = ReminderTime.suggestedTime(now.toLocalDate(), now)
            val start = RoutineDraft(startMinute = suggested.hour * 60 + suggested.minute)
            initialDraft.value = start
            draft.value = start
        } else {
            // O banco alimenta os buffers de texto (só entra sem digitação pendente) e o retrato.
            viewModelScope.launch {
                routine.collect { row ->
                    loaded.value = true
                    if (row == null) return@collect
                    if (snapshot == null) snapshot = RoutineDraft.from(row)
                    titleBuffer.onStored(row.title)
                    notesBuffer.onStored(row.notes)
                }
            }
        }
    }

    /** Enfileira uma mudança calculada sobre a linha MAIS RECENTE; devolve o trabalho (para esperar). */
    private fun enqueue(change: (Routine) -> RoutineChange?): Job =
        enqueueAction { row -> change(row)?.let { repository.edit(routineId, it) } }

    /** Enfileira uma ação qualquer sobre a linha MAIS RECENTE, na ordem dos toques. */
    private fun enqueueAction(action: suspend (Routine) -> Unit): Job = writes.enqueue(action)

    /** Espera as gravações já enfileiradas terminarem (barreira; o duplicar usa, e os testes também). */
    internal suspend fun awaitPendingWrites() = writes.awaitIdle()

    /** Edição: marca o campo como mexido e enfileira a mudança. */
    private fun send(field: Field, change: (Routine) -> RoutineChange?) {
        touched.update { it + field }
        enqueue(change)
    }

    // --- Campos -------------------------------------------------------------------------------------

    /** Criação: o texto dos campos como estado de composição, escrito na própria tecla (E3-A). */
    private var newTitle by mutableStateOf("")
    private var newNotes by mutableStateOf("")

    /**
     * O texto que os CAMPOS mostram, lido direto pela tela: estado de composição escrito na própria tecla
     * (rascunho na criação, [TextFieldBuffer.fieldText] na edição). Nunca o texto que volta pelo
     * [uiState]: em rajada ele chegava atrasado e o campo trocava ou perdia letras (E3-A da Coruja).
     */
    val titleInput: String get() = if (isNew) newTitle else titleBuffer.fieldText
    val notesInput: String get() = if (isNew) newNotes else notesBuffer.fieldText

    fun updateTitle(title: String) {
        if (isNew) {
            newTitle = title
            draft.update { it?.copy(title = title) }
        } else {
            touched.update { it + Field.TITLE }
            titleBuffer.onType(title)
        }
    }

    fun updateNotes(notes: String) {
        if (isNew) {
            newNotes = notes
            draft.update { it?.copy(notes = notes) }
        } else {
            touched.update { it + Field.NOTES }
            notesBuffer.onType(notes)
        }
    }

    fun updateIcon(icon: RoutineIcon) =
        if (isNew) draft.update { it?.copy(icon = icon) } else send(Field.ICON) { RoutineChange.Icon(icon) }

    fun updateColor(color: RoutineColor) =
        if (isNew) draft.update { it?.copy(color = color) } else send(Field.COLOR) { RoutineChange.Color(color) }

    fun updateStart(time: LocalTime) {
        val minute = time.hour * 60 + time.minute
        if (isNew) draft.update { it?.copy(startMinute = minute) } else send(Field.START) { RoutineChange.Start(minute) }
    }

    fun updateDuration(minutes: Int) =
        if (isNew) {
            draft.update { it?.copy(durationMinutes = minutes) }
        } else {
            send(Field.DURATION) { RoutineChange.Duration(minutes) }
        }

    /** "Termina às": a duração sai da diferença até o fim, a partir do início gravado na hora. */
    fun updateEnd(time: LocalTime) {
        val end = time.hour * 60 + time.minute
        if (isNew) {
            draft.update { it?.withEnd(end) }
        } else {
            send(Field.DURATION) { row -> RoutineChange.Duration(RoutineDraft.from(row).withEnd(end).durationMinutes) }
        }
    }

    /** O último dia marcado não desmarca (5.3): o toque que zeraria os dias não faz nada. */
    fun toggleDay(day: DayOfWeek) {
        if (isNew) {
            draft.update { current ->
                val mask = current?.let { RoutineDays.toggle(it.daysOfWeek, day) }
                if (current == null || mask == null || mask and RoutineDays.ALL == 0) current else current.copy(daysOfWeek = mask)
            }
        } else {
            send(Field.DAYS) { row ->
                RoutineDays.toggle(row.daysOfWeek, day).takeIf { it and RoutineDays.ALL != 0 }?.let(RoutineChange::Days)
            }
        }
    }

    fun setDays(mask: Int) =
        if (isNew) draft.update { it?.copy(daysOfWeek = mask) } else send(Field.DAYS) { RoutineChange.Days(mask) }

    fun updateRemind(remind: Boolean) =
        if (isNew) draft.update { it?.copy(remind = remind) } else send(Field.REMIND) { RoutineChange.Remind(remind) }

    fun updateCategory(categoryId: Long?) =
        if (isNew) {
            draft.update { it?.copy(categoryId = categoryId) }
        } else {
            send(Field.CATEGORY) { RoutineChange.Category(categoryId) }
        }

    fun updateWeekInterval(weeks: Int) {
        val clamped = weeks.coerceIn(1, RoutineDraft.MAX_WEEK_INTERVAL)
        if (isNew) {
            draft.update { it?.copy(weekInterval = clamped) }
        } else {
            send(Field.WEEK_INTERVAL) { RoutineChange.WeekInterval(clamped) }
        }
    }

    /** Início da vigência; depois do fim, limpa o fim (5.3). */
    fun updateStartDate(epochDay: Long?) {
        if (isNew) {
            draft.update { current ->
                current?.copy(
                    startEpochDay = epochDay,
                    endEpochDay = current.endEpochDay.takeUnless { end -> epochDay != null && end != null && epochDay > end },
                )
            }
        } else {
            send(Field.VALIDITY) { RoutineChange.ValidFrom(epochDay) }
        }
    }

    /** Fim da vigência; antes do início não é aceito (o seletor nem oferece, 5.3). */
    fun updateEndDate(epochDay: Long?) {
        if (isNew) {
            draft.update { current ->
                val start = current?.startEpochDay
                if (epochDay != null && start != null && epochDay < start) current else current?.copy(endEpochDay = epochDay)
            }
        } else {
            send(Field.VALIDITY) { RoutineChange.ValidUntil(epochDay) }
        }
    }

    // --- Lista de compras (ADR-0001) ----------------------------------------------------------------

    /** "Usar lista existente". */
    fun attachShoppingList(listId: Long) {
        if (isNew) {
            pendingListName.value = null
            draft.update { it?.copy(shoppingListId = listId) }
        } else {
            send(Field.LIST) { RoutineChange.ShoppingList(listId) }
        }
    }

    /**
     * "Criar nova". Na edição, cria e vincula numa transação. Na criação, só guarda o nome: a lista
     * nasce junto com a rotina no "Criar rotina" (15.5), e voltar sem criar não deixa lista avulsa.
     */
    fun createShoppingList(name: String) {
        if (name.isBlank()) return
        if (isNew) {
            pendingListName.value = name.trim()
            draft.update { it?.copy(shoppingListId = null) }
        } else {
            send(Field.LIST) { RoutineChange.NewShoppingList(name) }
        }
    }

    /** "Desvincular": a lista e os itens continuam na aba Listas. */
    fun detachShoppingList() {
        if (isNew) {
            pendingListName.value = null
            draft.update { it?.copy(shoppingListId = null) }
        } else {
            send(Field.LIST) { RoutineChange.ShoppingList(null) }
        }
    }

    // --- Lista de exercícios (T34): só vale para uma rotina já criada. ------------------------------

    fun addExerciseItem(name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        if (isNew || name.isBlank()) return
        enqueueAction { exerciseRepository.add(routineId, name, sets, reps, load, restSeconds) }
    }

    fun updateExerciseItem(item: ExerciseItem, name: String, sets: Int, reps: Int, load: String?, restSeconds: Int?) {
        if (name.isBlank()) return
        enqueueAction { exerciseRepository.update(item, name, sets, reps, load, restSeconds) }
    }

    fun removeExerciseItem(item: ExerciseItem) {
        enqueueAction { exerciseRepository.remove(item) }
    }

    /** Reordena o exercício para cima ([up] = true) ou para baixo, trocando a posição com o vizinho. */
    fun moveExerciseItem(item: ExerciseItem, up: Boolean) {
        enqueueAction { exerciseRepository.move(item, up) }
    }

    // --- Ações --------------------------------------------------------------------------------------

    /** Criação: aplica um modelo; a lista já escolhida (existente ou nova) é mantida. */
    fun applyDraft(newDraft: RoutineDraft) {
        if (!isNew) return
        newTitle = newDraft.title
        newNotes = newDraft.notes
        draft.update { current -> newDraft.copy(shoppingListId = current?.shoppingListId ?: newDraft.shoppingListId) }
    }

    /**
     * "Criar rotina": grava a rotina (e a lista nova, se houver, na mesma transação), agenda e FECHA a
     * tela (P3). No escopo do app: voltar logo depois do toque não corta a criação. Um toque só vale.
     */
    fun create() {
        val current = draft.value ?: return
        if (!isNew || !current.isValid || creating.value) return
        creating.value = true
        val listName = pendingListName.value
        editScope.launch {
            try {
                repository.add(current.toNewRoutine(now = clock.nowMillis()), newListName = listName)
                eventChannel.send(Event.Closed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Falhou: o botão volta a valer e o rascunho continua na tela (N1 da revisão da E3).
                Log.w(TAG, "Criar rotina falhou", error)
                creating.value = false
            }
        }
    }

    /**
     * "Excluir rotina" (P8): grava o texto pendente, arquiva, oferece o Desfazer do app e fecha; a
     * mensagem aparece na tela de origem. Com o salvar na hora não há rascunho a descartar: o Desfazer
     * devolve a rotina como está gravada. Arquivar e oferecer é UMA operação do [UndoOffers], no
     * escopo do app (NB-E3-C). Exclui a linha MAIS RECENTE, lida depois de as gravações pendentes
     * terminarem (barreira na fila), para o Desfazer devolver o que a pessoa via (A2 da revisão da E2).
     */
    fun delete() {
        if (isNew || leaving) return
        leaving = true
        flushPending()
        editScope.launch {
            awaitPendingWrites()
            val latest = repository.observeRoutine(routineId).first() ?: return@launch
            undoOffers.deleteRoutine(latest)
            eventChannel.send(Event.Closed)
        }
    }

    /**
     * Duplica a rotina como uma nova ([copyTitle]), a partir da linha gravada depois das gravações
     * pendentes. A cópia nasce ativa, sem pausa e sem lista (ADR-0001 4.3).
     */
    fun duplicate(copyTitle: String) {
        if (isNew || leaving) return
        leaving = true
        flushPending()
        editScope.launch {
            awaitPendingWrites()
            val latest = repository.observeRoutine(routineId).first() ?: return@launch
            repository.add(
                latest.copy(
                    id = NEW_ROUTINE_ID,
                    title = copyTitle,
                    createdAt = clock.nowMillis(),
                    archivedAt = null,
                    pausedAt = null,
                    shoppingListId = null,
                ),
            )
            eventChannel.send(Event.Closed)
        }
    }

    /**
     * Pausa ou retoma (grava só a pausa); não fecha o editor. Decide pela linha lida NA HORA, pela fila
     * (N2 da revisão da E3): dois toques rápidos pausam e retomam, em vez de pausar duas vezes.
     */
    fun togglePause() {
        if (isNew) return
        enqueueAction { row -> if (row.pausedAt == null) repository.pause(row) else repository.resume(row) }
    }

    /**
     * "Reverter alterações" (P2): manda de volta SÓ os campos mexidos nesta sessão (N4), com os valores
     * do retrato da abertura, e devolve os exercícios ao que eram. Não mexe no que não é do editor
     * (conclusão, pausa, arquivamento) nem num campo que só mudou por fora.
     *
     * Lista: o vínculo volta ao do retrato. Uma lista CRIADA nesta sessão continua existindo, avulsa,
     * na aba Listas (a regra de sempre das listas, N4 da revisão da E3).
     */
    fun revert() {
        if (isNew) return
        val base = snapshot ?: return
        val fieldsToRevert = touched.value
        fieldsToRevert.forEach { field ->
            when (field) {
                Field.TITLE -> {
                    titleBuffer.reset(base.title)
                    enqueue { RoutineChange.Title(base.title) }
                }
                Field.NOTES -> {
                    notesBuffer.reset(base.notes)
                    enqueue { RoutineChange.Notes(base.notes) }
                }
                Field.START -> enqueue { RoutineChange.Start(base.startMinute) }
                Field.DURATION -> enqueue { RoutineChange.Duration(base.durationMinutes) }
                Field.DAYS -> enqueue { RoutineChange.Days(base.daysOfWeek) }
                Field.WEEK_INTERVAL -> enqueue { RoutineChange.WeekInterval(base.weekInterval) }
                Field.VALIDITY -> {
                    // Início primeiro: o fim do retrato nunca vem antes do início do retrato.
                    enqueue { RoutineChange.ValidFrom(base.startEpochDay) }
                    enqueue { RoutineChange.ValidUntil(base.endEpochDay) }
                }
                Field.REMIND -> enqueue { RoutineChange.Remind(base.remind) }
                Field.CATEGORY -> enqueue { RoutineChange.Category(base.categoryId) }
                Field.COLOR -> enqueue { RoutineChange.Color(base.color) }
                Field.ICON -> enqueue { RoutineChange.Icon(base.icon) }
                Field.LIST -> enqueue { RoutineChange.ShoppingList(base.shoppingListId) }
            }
        }
        touched.value = emptySet()
        val state = uiState.value
        if (state.exerciseItemsChanged) {
            state.exerciseBaseline?.let { baseline -> enqueueAction { exerciseRepository.replaceItems(routineId, baseline) } }
        }
    }

    /** Criação: confirmou o descarte ao voltar. Nada foi gravado; só fecha. */
    fun discardDraft() {
        viewModelScope.launch { eventChannel.send(Event.Closed) }
    }

    /** Grava já o texto pendente (a tela chama ao ir para segundo plano). */
    fun flushPending() {
        if (isNew) return
        titleBuffer.flush()
        notesBuffer.flush()
    }

    /**
     * Fecha o editor: grava o texto pendente (15.3) e só então fecha a fila, que termina ao esvaziar.
     * O onCleared chama; os testes também podem chamar (idempotente). Na thread principal: mexe nos
     * buffers (B2 da revisão da E2; a mesma forma no detalhe da tarefa).
     */
    internal fun finish() {
        flushPending()
        writes.close()
    }

    override fun onCleared() = finish()

    companion object {
        private const val TAG = "RoutineEditorVM"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutineEditorViewModel(
                    createSavedStateHandle(),
                    app.routineRepository,
                    app.routineCategoryRepository,
                    app.shoppingRepository,
                    app.exerciseRepository,
                    app.undoOffers,
                    app.editScope,
                    app.clock,
                )
            }
        }
    }
}
