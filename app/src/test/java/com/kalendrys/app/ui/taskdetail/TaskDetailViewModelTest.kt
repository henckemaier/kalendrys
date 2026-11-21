package com.kalendrys.app.ui.taskdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Detalhe da tarefa com salvar na hora (v1.9.0, ADR-0002 15.3, 15.4 e 15.8; E2 com os ajustes A1-A3
 * e N1 do Esquadro).
 *
 * A espera de 500 ms da digitação corre no relógio do Main de teste ([scheduler]), avançado à mão.
 * As gravações rodam de verdade, pela fila, no escopo do app e no Room: os testes esperam o BANCO
 * chegar ao estado ([awaitStored]) e, no fim, esperam a fila de cada ViewModel esvaziar antes de
 * fechar o banco (N7: não se cancela o escopo por cima das gravações).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskDetailViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository
    private lateinit var undoOffers: UndoOffers
    private lateinit var editScope: CoroutineScope
    private val viewModels = mutableListOf<TaskDetailViewModel>()
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
        undoOffers = testUndoOffers(testDb, tasks = taskRepository)
        editScope = testDb.appScope()
    }

    @After
    fun tearDown() {
        // N7: espera as gravações já pedidas terminarem ANTES de fechar o banco.
        runBlocking { viewModels.forEach { withTimeoutOrNull(TIMEOUT_MS) { it.awaitPendingWrites() } } }
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(taskId: Long) = testDb.track(
        TaskDetailViewModel(
            SavedStateHandle(mapOf(TASK_ID_ARG to taskId)),
            taskRepository,
            shoppingRepository,
            undoOffers,
            editScope,
        ),
    ).also { viewModels += it }

    /** Espera (tempo real) a tela chegar a um estado. */
    private suspend fun TaskDetailViewModel.awaitUi(predicate: (TaskDetailViewModel.UiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { uiState.first(predicate) } }

    /** Espera (tempo real) o banco chegar a um estado da tarefa. */
    private suspend fun awaitStored(id: Long, predicate: (Task) -> Boolean): Task =
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) { taskRepository.observeTask(id).filterNotNull().first(predicate) }
        }

    private suspend fun stored(id: Long): Task = taskRepository.observeTask(id).filterNotNull().first()

    /** Passa a espera de 500 ms da digitação (o relógio do Main de teste). */
    private fun passDebounce() {
        scheduler.advanceTimeBy(501)
        scheduler.runCurrent()
    }

    private suspend fun tomorrowTask(listId: Long? = null): Long =
        taskRepository.addTask(
            "Levar o carro",
            timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)),
            shoppingListId = listId,
        ).id

    /** A oferta do app (no escopo do app, tempo real). */
    private suspend fun awaitOffer() = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT_MS) { undoOffers.pending.first { it != null } }
    }

    // --- Texto: buffer local, 500 ms, sair grava, título vazio (15.3) ---------------------------

    @Test
    fun `titulo grava 500 ms depois da ultima tecla, nao antes`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Levar o carro na revisão")
        assertEquals("o campo mostra o digitado na hora", "Levar o carro na revisão", vm.uiState.value.title)
        scheduler.advanceTimeBy(499)
        scheduler.runCurrent()
        vm.awaitPendingWrites()
        assertEquals("gravou antes dos 500 ms", "Levar o carro", stored(id).title)

        passDebounce()
        awaitStored(id) { it.title == "Levar o carro na revisão" }
        assertTrue(vm.uiState.value.canRevert)
    }

    @Test
    fun `sair no meio da digitacao grava`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateNotes("Levar os documentos")
        vm.updateLocation("Oficina do centro")
        // A tela fecha antes dos 500 ms: o fim do ViewModel grava pela fila, no escopo do app.
        vm.viewModelScope.cancel()

        val after = awaitStored(id) { it.notes == "Levar os documentos" && it.location == "Oficina do centro" }
        assertEquals("Levar o carro", after.title)
    }

    @Test
    fun `segundo plano grava na hora pelo flush`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Levar o carro amanhã cedo")
        vm.flush()

        awaitStored(id) { it.title == "Levar o carro amanhã cedo" }
        Unit
    }

    @Test
    fun `titulo vazio nao grava e ao sair o campo volta ao ultimo valido`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("")
        passDebounce()
        vm.flush()
        vm.awaitPendingWrites()
        assertEquals("o campo mostra o vazio enquanto a tela está aberta", "", vm.uiState.value.title)

        vm.leave()
        assertEquals("Levar o carro", vm.awaitUi { it.title.isNotEmpty() }.title)
        vm.awaitPendingWrites()
        assertEquals("o vazio nunca chega ao banco", "Levar o carro", stored(id).title)
    }

    @Test
    fun `sair com titulo valido ainda gravando nao devolve o titulo antigo`() = runBlocking {
        // N1: só o pendente RECUSADO volta ao último válido; um válido a caminho do banco fica.
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Levar o carro já")
        vm.leave()

        assertEquals("Levar o carro já", vm.uiState.value.title)
        awaitStored(id) { it.title == "Levar o carro já" }
        Unit
    }

    @Test
    fun `texto mudado por fora so entra no campo sem digitacao pendente`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        // Sem digitação pendente: o que veio de fora aparece.
        taskRepository.edit(id, TaskChange.Notes("Vindo de fora"))
        vm.awaitUi { it.notes == "Vindo de fora" }

        // Com digitação pendente: o campo fica com o digitado, e é ele que fica gravado no fim.
        vm.updateNotes("Digitado aqui")
        taskRepository.edit(id, TaskChange.Notes("Outra de fora"))
        awaitStored(id) { it.notes == "Outra de fora" }
        assertEquals("Digitado aqui", vm.uiState.value.notes)

        passDebounce()
        awaitStored(id) { it.notes == "Digitado aqui" }
        Unit
    }

    @Test
    fun `espaco no fim do que se digita nao some com o eco da gravacao`() = runBlocking {
        // N3: o banco devolve aparado; o eco da própria gravação não troca o campo.
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateLocation("Oficina ")
        passDebounce()
        awaitStored(id) { it.location == "Oficina" }
        vm.awaitPendingWrites()

        assertEquals("Oficina ", vm.uiState.value.location)
    }

    // --- Aceite da 15.8: conclusão pela notificação com digitação pendente ----------------------

    @Test
    fun `concluir pela notificacao com o detalhe aberto nao perde a digitacao nem a conclusao`() = runBlocking {
        val today = LocalDate.now()
        val id = taskRepository.addTask(
            "Remédio",
            timing = TaskTiming(date = today, time = LocalTime.of(9, 0), repeat = ReminderRepeat.DAILY),
        ).id
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Remédio da pressão")
        // O Concluir da notificação chega no meio da digitação.
        assertTrue(taskRepository.completeFromReminder(id, today, now = System.currentTimeMillis()))
        vm.awaitUi { it.doneAt != null }
        assertEquals("a digitação segue no campo", "Remédio da pressão", vm.uiState.value.title)

        vm.viewModelScope.cancel() // sai da tela

        awaitStored(id) { it.title == "Remédio da pressão" }
        assertEquals("a conclusão ficou", 1, db.completionLogDao().all().count { it.taskId == id })
    }

    // --- Data, hora e repetição (Timing) --------------------------------------------------------

    @Test
    fun `duas mudancas de data e repeticao seguidas, sem esperar o banco, valem as duas`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }.timing!!

        // A3: a segunda é montada sobre o que a tela mostra, que já é a primeira (ainda não gravada).
        vm.updateTiming(opened.copy(time = null))
        vm.updateTiming(vm.uiState.value.timing!!.copy(repeat = ReminderRepeat.WEEKLY))
        vm.awaitPendingWrites()

        val after = stored(id)
        assertEquals(ReminderRepeat.WEEKLY, after.repeat)
        assertNull("a primeira (tirar a hora) não foi desfeita", after.reminderAt)
        assertEquals(opened.date!!.toEpochDay(), after.dueDate)
    }

    @Test
    fun `lembrete escolhido aqui marca a mudanca, o da abertura nao`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }
        assertFalse(opened.reminderChanged)

        vm.updateTiming(opened.timing!!.copy(time = LocalTime.of(10, 0)))
        assertTrue(vm.awaitUi { it.reminderChanged }.reminderChanged)
    }

    // --- Concluir e desfazer (14.2, P6) ---------------------------------------------------------

    @Test
    fun `concluir pelo detalhe passa pela oferta do app`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.complete()

        val logId = (awaitOffer()!!.target as UndoTarget.CompletedTask).logId
        assertEquals(listOf(logId), db.completionLogDao().all().filter { it.taskId == id }.map { it.id })
        assertEquals(logId, vm.awaitUi { it.doneLogId != null }.doneLogId)
    }

    @Test
    fun `P6 - repetida concluida hoje desfaz essa conclusao e o historico de outro dia fica`() = runBlocking {
        val today = LocalDate.now()
        val id = taskRepository.addTask(
            "Treino",
            timing = TaskTiming(date = today.minusDays(3), time = LocalTime.of(7, 0), repeat = ReminderRepeat.DAILY),
        ).id
        val yesterday = today.minusDays(1).atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
        val old = taskRepository.logCompletion(id, completedAt = yesterday)
        val todays = taskRepository.logCompletion(id)
        val vm = viewModel(id)
        assertEquals(todays, vm.awaitUi { it.doneLogId != null }.doneLogId)

        vm.undoCompletion()

        vm.awaitUi { it.doneAt == null && it.history.size == 1 }
        assertEquals(listOf(old), db.completionLogDao().all().filter { it.taskId == id }.map { it.id })
    }

    @Test
    fun `P6 - unica encerrada desfaz a conclusao e volta a pendente`() = runBlocking {
        val id = taskRepository.addTask("Ligar", timing = TaskTiming(date = LocalDate.now())).id
        taskRepository.logCompletion(id)
        val vm = viewModel(id)
        vm.awaitUi { it.doneLogId != null }

        vm.undoCompletion()

        vm.awaitUi { it.doneAt == null }
        assertTrue(db.completionLogDao().all().none { it.taskId == id })
    }

    // --- Reverter alterações (P2, N4) -----------------------------------------------------------

    @Test
    fun `reverter devolve so os campos mexidos e nao a data reescrita por uma conclusao por fora`() = runBlocking {
        val id = tomorrowTask()
        val opened = stored(id)
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }
        assertFalse(vm.uiState.value.canRevert)

        vm.updateTitle("Levar o carro ao lava-rápido")
        vm.flush()
        awaitStored(id) { it.title == "Levar o carro ao lava-rápido" }
        // Por fora: concluída adiantada, a data veio para hoje.
        taskRepository.logCompletion(id)
        val completed = awaitStored(id) { it.dueDate == LocalDate.now().toEpochDay() }

        vm.revert()
        vm.awaitPendingWrites()

        val after = stored(id)
        assertEquals(opened.title, after.title)
        assertEquals("a data da conclusão por fora fica", completed.dueDate, after.dueDate)
        assertEquals(completed.reminderAt, after.reminderAt)
        assertFalse(vm.awaitUi { !it.canRevert }.canRevert)
    }

    @Test
    fun `reverter com digitacao pendente esquece a digitacao e devolve o original`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Gravado")
        vm.flush()
        vm.updateTitle("Pendente")
        vm.revert()
        passDebounce()
        vm.awaitPendingWrites()

        assertEquals("Levar o carro", stored(id).title)
        assertEquals("Levar o carro", vm.awaitUi { it.title == "Levar o carro" }.title)
    }

    @Test
    fun `reverter data e hora devolve o timing da abertura`() = runBlocking {
        val id = tomorrowTask()
        val opened = stored(id)
        val vm = viewModel(id)
        val timing = vm.awaitUi { it.timing != null }.timing!!

        vm.updateTiming(timing.copy(date = timing.date!!.plusDays(2), repeat = ReminderRepeat.WEEKLY))
        vm.revert()
        vm.awaitPendingWrites()

        val after = stored(id)
        assertEquals(ReminderRepeat.NONE, after.repeat)
        assertEquals(opened.dueDate, after.dueDate)
        assertEquals(opened.reminderAt, after.reminderAt)
    }

    // --- Lista de compras (ADR-0001): grava na hora --------------------------------------------

    @Test
    fun `tarefa com lista mostra o resumo sem contar como alteracao`() = runBlocking {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 2, priceCents = 500)
        val id = db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))

        val state = viewModel(id).awaitUi { it.shoppingList?.progress?.total == 1 }
        assertEquals("Feira", state.shoppingList?.name)
        assertEquals(1_000L, state.shoppingList?.totalCents)
        assertEquals(listOf(ShoppingListOwner.Kind.TASK to id), state.shoppingList?.owners?.map { it.kind to it.id })
        assertFalse(state.canRevert)
    }

    @Test
    fun `usar lista existente e desvincular gravam na hora e a lista fica`() = runBlocking {
        val listId = shoppingRepository.createList("Mercado")
        shoppingRepository.add(listId, "Café")
        val id = db.taskDao().insert(Task(title = "Mercado"))
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.attachShoppingList(listId)
        vm.awaitPendingWrites()
        assertEquals(listId, stored(id).shoppingListId)
        vm.awaitUi { it.shoppingList?.id == listId }

        vm.detachShoppingList()
        vm.awaitPendingWrites()
        assertNull(stored(id).shoppingListId)
        assertNotNull("a lista continua", shoppingRepository.observeSummary(listId).first())
    }

    @Test
    fun `criar nova vincula numa transacao e reverter deixa a lista avulsa`() = runBlocking {
        val id = db.taskDao().insert(Task(title = "Churrasco"))
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.createShoppingList("Churrasco")
        vm.awaitPendingWrites()
        val linked = stored(id).shoppingListId!!

        vm.revert()
        vm.awaitPendingWrites()
        assertNull(stored(id).shoppingListId)
        // Aceito pelo Esquadro: a lista criada na sessão fica avulsa na aba Listas.
        assertEquals("Churrasco", shoppingRepository.observeSummary(linked).first()?.name)
    }

    @Test
    fun `vinculo orfao equivale a sem lista`() = runBlocking {
        val id = db.taskDao().insert(Task(title = "Órfã", shoppingListId = 999))
        val state = viewModel(id).awaitUi { it.task != null }
        assertNull(state.shoppingList)
    }

    // --- Excluir pelo detalhe com Desfazer na origem (v1.8.0, E0-D; A2) -------------------------

    @Test
    fun `excluir pede ao objeto do app e fecha - a oferta vem com a tarefa como estava`() = runBlocking {
        val listId = shoppingRepository.createList("Feira")
        val id = tomorrowTask(listId)
        val before = stored(id)
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.delete()
        assertEquals(TaskDetailViewModel.Event.Deleted, withTimeout(TIMEOUT_MS) { vm.events.first() })

        val offered = (awaitOffer()!!.target as UndoTarget.DeletedTask).task
        assertEquals(before, offered)
        assertEquals(listId, offered.shoppingListId)
        assertNull(db.taskDao().byId(id))
    }

    @Test
    fun `excluir antes dos 500 ms e desfazer devolve o texto digitado`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Levar o carro e o estepe")
        vm.delete() // antes da espera: grava o pendente e espera a fila (A2)

        val offer = awaitOffer()!!
        assertEquals("Levar o carro e o estepe", (offer.target as UndoTarget.DeletedTask).task.title)
        withContext(Dispatchers.Default) { undoOffers.undo(offer).join() }
        assertEquals("Levar o carro e o estepe", awaitStored(id) { true }.title)
    }

    @Test
    fun `fechar a tela logo depois de pedir a exclusao ainda exclui e publica a oferta`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.delete()
        vm.viewModelScope.cancel()

        assertEquals(id, (awaitOffer()!!.target as UndoTarget.DeletedTask).task.id)
        assertNull(db.taskDao().byId(id))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
