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
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.repository.ShoppingListSummary
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.ui.edit.EditQueue
import com.kalendrys.app.ui.edit.TextFieldBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Detalhe da tarefa com SALVAR NA HORA (v1.9.0, ADR-0002 15.3 e 15.4). Sem rascunho nem base: a tela
 * mostra a linha do banco ([TaskRepository.observeTask]) e manda mudanças pelo [TaskRepository.edit],
 * uma por campo ([TaskChange]), que as aplica sobre a linha mais recente. Concluir, desfazer e excluir
 * a lista por fora, com a tela aberta, não são desfeitos por uma edição.
 *
 * - TODAS as gravações passam por UMA fila ([EditQueue], A1 do Esquadro), na ordem do toque: o
 *   [editScope] tem várias threads e dois `launch` seguidos não teriam ordem.
 * - Texto (título, observações, local): um [TextFieldBuffer] por campo. Grava 500 ms depois da última
 *   tecla e sempre em [flush] (segundo plano, pela tela) e em [finish] (fim do ViewModel), pela fila,
 *   no [editScope] do app. Título vazio não grava; ao sair, o campo volta ao último título válido.
 * - Data, hora e repetição: UMA mudança ([TaskChange.Timing]), enviada só quando a pessoa mexe nelas.
 *   A última enviada é mostrada até a fila gravá-la (A3): dois toques seguidos não se desfazem.
 *   Uma personalizada antiga sem dia utilizável (NB-E1-A) não é regravada por abrir a tela nem por
 *   mudar outro campo.
 * - Concluir passa pela oferta do app ([UndoOffers.completeTask]); concluída (hoje, numa repetida;
 *   encerrada, numa única), a ação principal desfaz ESSA conclusão (P6).
 * - Reverter alterações (P2): retrato da abertura; volta SÓ os campos mexidos na sessão (N4), um a um.
 */
class TaskDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: TaskRepository,
    private val shoppingRepository: ShoppingRepository,
    private val undoOffers: UndoOffers,
    /** Escopo das gravações, o do app ([KalendrysApp.editScope]): sobrevive à tela. */
    private val editScope: CoroutineScope,
    /** O relógio do app (v1.10.0, 16.3). */
    private val clock: AppClock,
) : ViewModel() {

    private val taskId: Long = checkNotNull(savedStateHandle[TASK_ID_ARG])
    /** O fuso da sessão da tela, lido do relógio do app ao abrir (como até a v1.9.x). */
    private val zone: ZoneId = clock.zone()

    /** Campos do detalhe que o Reverter conhece. */
    enum class Field { TITLE, NOTES, LOCATION, TIMING, SHOPPING_LIST }

    /** Retrato da abertura (primeira emissão do banco), para o Reverter. Não sobrevive ao processo. */
    private data class Snapshot(val task: Task, val timing: TaskTiming)

    data class UiState(
        val notFound: Boolean = false,
        val task: Task? = null,
        /**
         * O texto dos campos em fluxo (o buffer: digitação pendente ou o banco), para o resto do estado.
         * NÃO é a fonte do campo: a tela liga os TextFields a [titleInput], [notesInput] e
         * [locationInput], que são estado de composição (E3-A).
         */
        val title: String = "",
        val notes: String = "",
        val location: String = "",
        /** Data, hora e repetição como o banco as tem (leitura tolerante, [TaskTiming.of]). */
        val timing: TaskTiming? = null,
        val history: List<CompletionLog> = emptyList(),
        /**
         * Quando foi feita: hoje, numa tarefa que se repete; a conclusão mais recente, numa que
         * não se repete (feita uma vez, está encerrada). null = pendente.
         */
        val doneAt: Long? = null,
        /** O registro da conclusão de [doneAt], o que "Desfazer conclusão" apaga (P6). */
        val doneLogId: Long? = null,
        val zone: ZoneId,
        /** Lista anexada (do banco); null sem lista ou com vínculo órfão (ADR-0001 4.1). */
        val shoppingList: ShoppingListSummary? = null,
        /** Todas as listas, para o "Usar lista existente" do seletor. */
        val availableLists: List<ShoppingListSummary> = emptyList(),
        /** Algum campo foi mexido nesta sessão: "Reverter alterações" aparece. */
        val canRevert: Boolean = false,
        /**
         * O lembrete mostrado não é mais o da abertura: se já passou, a tela avisa que ele não vai
         * tocar. O que já estava salvo pode ter tocado e não avisa.
         */
        val reminderChanged: Boolean = false,
    ) {
        val repeats: Boolean get() = task != null && task.repeat != ReminderRepeat.NONE
    }

    sealed interface Event {
        data object Deleted : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /**
     * A fila das gravações do detalhe (A1): uma por vez, na ordem em que foram pedidas, no escopo do
     * app. Fechada no fim do ViewModel ([finish]), depois do [leave].
     */
    private val writes = EditQueue(editScope) { repository.observeTask(taskId).first() }

    private fun enqueue(change: TaskChange): Job = writes.enqueue { repository.edit(taskId, change, zone) }

    /** Barreira: espera as gravações já pedidas terminarem (excluir; testes, N7). */
    internal suspend fun awaitPendingWrites() = writes.awaitIdle()

    /**
     * O texto que os CAMPOS mostram: estado de composição do [TextFieldBuffer], escrito na própria tecla
     * (E3-A da Coruja: em rajada, o texto que voltava pelo UiState chegava atrasado e o campo trocava
     * ou perdia letras). A tela liga os TextFields a estes, nunca ao [UiState.title] e companhia.
     */
    val titleInput: String get() = titleBuffer.fieldText
    val notesInput: String get() = notesBuffer.fieldText
    val locationInput: String get() = locationBuffer.fieldText

    private fun writer(change: (String) -> TaskChange): (String) -> Job = { value -> enqueue(change(value)) }

    private val titleBuffer = TextFieldBuffer(
        timerScope = viewModelScope,
        writeScope = editScope,
        isValid = { it.isNotBlank() },
        write = writer(TaskChange::Title),
    )
    private val notesBuffer = TextFieldBuffer(viewModelScope, editScope, write = writer(TaskChange::Notes))
    private val locationBuffer = TextFieldBuffer(viewModelScope, editScope, write = writer(TaskChange::Location))

    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private val touched = MutableStateFlow<Set<Field>>(emptySet())
    private val deleting = MutableStateFlow(false)

    /** Último [TaskTiming] enviado e ainda não gravado pela fila (A3): a tela mostra ele, não o banco. */
    private val pendingTiming = MutableStateFlow<TaskTiming?>(null)

    /** Último título gravado (sempre válido), para o [leave] devolver ao campo um título vazio. */
    private var storedTitle: String? = null

    private val task: Flow<Task?> = repository.observeTask(taskId)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lists: Flow<Pair<ShoppingListSummary?, List<ShoppingListSummary>>> =
        combine(
            task.map { it?.shoppingListId }
                .distinctUntilChanged()
                .flatMapLatest { listId -> if (listId == null) flowOf(null) else shoppingRepository.observeSummary(listId) },
            shoppingRepository.observeSummaries(),
        ) { linked, all -> linked to all }

    private val texts: Flow<Triple<String?, String?, String?>> =
        combine(titleBuffer.text, notesBuffer.text, locationBuffer.text) { t, n, l -> Triple(t, n, l) }

    private data class Session(
        val snapshot: Snapshot?,
        val touched: Set<Field>,
        val deleting: Boolean,
        val pendingTiming: TaskTiming?,
    )

    private val session: Flow<Session> = combine(snapshot, touched, deleting, pendingTiming, ::Session)

    val uiState: StateFlow<UiState> =
        combine(task, repository.observeCompletions(taskId), lists, texts, session) {
                task, logs, (linked, available), (title, notes, location), session ->
            val snap = session.snapshot
            val today = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()
            val latest = logs.firstOrNull()
            val doneAt = when {
                latest == null -> null
                task != null && task.repeat == ReminderRepeat.NONE ->
                    latest.completedAt.takeIf { TaskSchedule.isFinished(task, it, zone) }
                Instant.ofEpochMilli(latest.completedAt).atZone(zone).toLocalDate() == today -> latest.completedAt
                else -> null
            }
            val timing = task?.let { session.pendingTiming ?: TaskTiming.of(it, zone) }
            UiState(
                // Depois de excluir, a tela fecha; sem o "não encontrada" piscando no meio.
                notFound = task == null && !session.deleting,
                task = task,
                title = title ?: task?.title.orEmpty(),
                notes = notes ?: task?.notes.orEmpty(),
                location = location ?: task?.location.orEmpty(),
                timing = timing,
                history = logs,
                doneAt = doneAt,
                doneLogId = latest?.id?.takeIf { doneAt != null },
                zone = zone,
                shoppingList = linked,
                availableLists = available,
                canRevert = snap != null && session.touched.isNotEmpty(),
                reminderChanged = snap != null && timing != null && !timing.keepsReminderOf(snap.task, zone),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(zone = zone))

    init {
        viewModelScope.launch {
            task.filterNotNull().collect { task ->
                if (snapshot.value == null) snapshot.value = Snapshot(task, TaskTiming.of(task, zone))
                storedTitle = task.title
                // Texto que mudou por fora só entra no campo sem digitação pendente (o buffer decide).
                titleBuffer.onStored(task.title)
                notesBuffer.onStored(task.notes)
                locationBuffer.onStored(task.location)
            }
        }
    }

    private fun touch(field: Field) = touched.update { it + field }

    fun updateTitle(title: String) {
        touch(Field.TITLE)
        titleBuffer.onType(title)
    }

    fun updateNotes(notes: String) {
        touch(Field.NOTES)
        notesBuffer.onType(notes)
    }

    fun updateLocation(location: String) {
        touch(Field.LOCATION)
        locationBuffer.onType(location)
    }

    /**
     * Data, hora e repetição: gravam na hora, juntas ([TaskChange.Timing]). Inválida não grava. A tela
     * monta o [TaskTiming] inteiro sobre o que mostra; por isso o enviado é mostrado até gravar (A3).
     */
    fun updateTiming(timing: TaskTiming) {
        touch(Field.TIMING)
        pendingTiming.value = timing
        lastSentTiming = timing
        enqueue(TaskChange.Timing(timing)).invokeOnCompletion { pendingTiming.compareAndSet(timing, null) }
    }

    /**
     * O último conjunto de data, hora e repetição que ESTE editor enviou (OBS-2, decisão do Esquadro):
     * o Reverter só devolve o retrato se o banco ainda estiver com ele. Só na thread principal.
     */
    private var lastSentTiming: TaskTiming? = null

    // --- Lista de compras (ADR-0001): grava na hora; os itens são da tela da lista. ---

    /** "Usar lista existente". Lista excluída no meio não é vinculada (o repositório confere). */
    fun attachShoppingList(listId: Long) {
        touch(Field.SHOPPING_LIST)
        enqueue(TaskChange.ShoppingList(listId))
    }

    /** "Criar nova": cria e vincula numa transação só. */
    fun createShoppingList(name: String) {
        if (name.isBlank()) return
        touch(Field.SHOPPING_LIST)
        enqueue(TaskChange.NewShoppingList(name))
    }

    /** "Desvincular": a lista e os itens continuam na aba Listas. */
    fun detachShoppingList() {
        touch(Field.SHOPPING_LIST)
        enqueue(TaskChange.ShoppingList(null))
    }

    /** Grava já a digitação pendente (a tela chama ao ir para segundo plano). */
    fun flush() {
        titleBuffer.flush()
        notesBuffer.flush()
        locationBuffer.flush()
    }

    /**
     * Saindo da tela: grava o pendente e, se o título ficou vazio (não gravável), o campo volta ao
     * último título válido (5.3); o banco nunca recebeu o vazio.
     */
    fun leave() {
        flush()
        // Só o pendente RECUSADO (N1): um título válido ainda gravando não volta (nem pisca o antigo).
        if (titleBuffer.hasRejectedPending) storedTitle?.let(titleBuffer::reset)
    }

    /** Concluir pela oferta do app (14.2): a mensagem com Desfazer aparece mesmo se a tela fechar. */
    /**
     * Concluir pela oferta do app, como AÇÃO DA FILA (NB-E2-A do Lince): roda depois das gravações
     * pendentes (uma data mudada logo antes não grava por cima da conclusão nem reabre a tarefa), sobre a
     * linha mais recente.
     */
    fun complete() {
        if (uiState.value.task == null) return
        flush()
        writes.enqueue { latest -> undoOffers.completeTask(latest).join() }
    }

    /** P6: desfaz a conclusão mostrada (a de hoje, numa repetida; a que encerrou, numa única). */
    fun undoCompletion() {
        val logId = uiState.value.doneLogId ?: return
        // Desfazer direto: a oferta pendente dessa conclusão sai da tela (E4-B).
        undoOffers.discardTaskCompletion(logId)
        writes.enqueue { repository.undoCompletion(logId) }
    }

    /**
     * Reverter alterações (P2, N4): manda de volta, um a um, SÓ os campos mexidos nesta sessão, com
     * o valor do retrato da abertura. O que mudou por fora (a data reescrita por uma conclusão, uma
     * lista excluída) e não foi mexido aqui fica como está. Data, hora e repetição voltam como o
     * retrato as leu ([TaskTiming.of]; uma personalizada antiga sem dia volta como "não repete").
     *
     * Vai pela fila, DEPOIS de esquecer a digitação pendente: nenhuma gravação pedida antes passa por
     * cima. Uma lista criada nesta sessão ("Criar nova") não é apagada: fica avulsa na aba Listas, só
     * sem o vínculo (aceito pelo Esquadro).
     *
     * Data, hora e repetição PULAM quando mudaram por fora (OBS-2, decisão do Esquadro): só voltam ao
     * retrato se a linha mais recente, lida pela fila na hora de gravar, ainda for a do último conjunto
     * que este editor enviou (reaplicá-lo não mudaria nada). Uma conclusão adiantada feita depois, que
     * trouxe a data para hoje, fica; desfeita a conclusão, a data volta a ser a enviada e o Reverter vale.
     */
    fun revert() {
        val snap = snapshot.value ?: return
        val fields = touched.value
        if (fields.isEmpty()) return
        val original = snap.task
        // Os buffers esquecem a digitação pendente antes: nenhuma gravação atrasada vem depois.
        if (Field.TITLE in fields) titleBuffer.reset(original.title)
        if (Field.NOTES in fields) notesBuffer.reset(original.notes)
        if (Field.LOCATION in fields) locationBuffer.reset(original.location)
        val changes = fields.sortedBy { it.ordinal }.map { field ->
            when (field) {
                Field.TITLE -> TaskChange.Title(original.title)
                Field.NOTES -> TaskChange.Notes(original.notes)
                Field.LOCATION -> TaskChange.Location(original.location)
                Field.TIMING -> null // pela fila, com a guarda abaixo
                Field.SHOPPING_LIST -> TaskChange.ShoppingList(original.shoppingListId)
            }
        }
        val sentTiming = lastSentTiming.takeIf { Field.TIMING in fields }
        touched.value = emptySet()
        pendingTiming.value = null
        lastSentTiming = null
        changes.filterNotNull().forEach(::enqueue)
        if (sentTiming != null) {
            writes.enqueue { latest ->
                val stillOurs = sentTiming.applyTo(latest, zone) == latest
                if (stillOurs) repository.edit(taskId, TaskChange.Timing(snap.timing), zone)
            }
        }
    }

    /**
     * Exclui (mesmo caminho do deslize na lista), oferece o Desfazer e fecha a tela (v1.8.0, E0-D).
     * Antes grava a digitação pendente e entra na fila depois dela (A2, B1): a oferta leva a tarefa
     * como a pessoa a via, e o Desfazer devolve o que ela digitou. Excluir e oferecer é UMA operação do [UndoOffers],
     * no escopo do app (NB-E3-C).
     */
    fun delete() {
        if (uiState.value.task == null) return
        deleting.value = true
        flush()
        // Uma AÇÃO DA FILA (B1), enfileirada aqui, já, depois do pendente: recebe a linha mais recente
        // (com o que a pessoa digitou) e roda mesmo que a tela feche e a fila seja fechada em seguida.
        writes.enqueue { latest -> undoOffers.deleteTask(latest).join() }
        viewModelScope.launch { eventChannel.send(Event.Deleted) }
    }

    /**
     * Fim do ViewModel (B2): grava o pendente ([leave]) e fecha a fila; o que já está nela termina.
     * Na thread principal, pelo [onCleared] (os testes chamam direto, antes de fechar o banco).
     */
    internal fun finish() {
        leave()
        writes.close()
    }

    override fun onCleared() = finish()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                TaskDetailViewModel(
                    createSavedStateHandle(),
                    app.repository,
                    app.shoppingRepository,
                    app.undoOffers,
                    app.editScope,
                    app.clock,
                )
            }
        }
    }
}
