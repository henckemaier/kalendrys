package com.kalendrys.app.ui.taskdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.linkList
import com.kalendrys.app.testutil.testUndoOffers
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
        shoppingRepository = ShoppingRepository(db, clock = SystemAppClock)
        undoOffers = testUndoOffers(testDb, tasks = taskRepository)
        editScope = testDb.appScope()
    }

    @After
    fun tearDown() {
        // N7: espera as gravações já pedidas terminarem ANTES de fechar o banco.
        runBlocking { viewModels.forEach { withTimeoutOrNull(TIMEOUT_MS) { it.awaitPendingWrites() } } }
        // B2: o fim do ViewModel (o que o onCleared faz), na thread do teste, antes de fechar o banco.
        viewModels.forEach { it.finish() }
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
            clock = SystemAppClock,
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
        ).id.also { if (listId != null) db.linkList(it, listId) }

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
        // O campo lê o estado de composição (titleInput), síncrono; o uiState é combinação e chega depois.
        assertEquals("o campo mostra o digitado na hora", "Levar o carro na revisão", vm.titleInput)
        scheduler.advanceTimeBy(499)
        scheduler.runCurrent()
        vm.awaitPendingWrites()
        assertEquals("gravou antes dos 500 ms", "Levar o carro", stored(id).title)

        passDebounce()
        awaitStored(id) { it.title == "Levar o carro na revisão" }
        assertTrue(vm.awaitUi { it.canRevert }.canRevert)
    }

    @Test
    fun `sair no meio da digitacao grava`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateNotes("Levar os documentos")
        vm.updateLocation("Oficina do centro")
        // A tela fecha antes dos 500 ms: o fim do ViewModel grava pela fila, no escopo do app.
        vm.finish()

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
        assertEquals("o campo mostra o vazio enquanto a tela está aberta", "", vm.titleInput)

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

        assertEquals("Levar o carro já", vm.titleInput)
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
        assertEquals("Digitado aqui", vm.notesInput)

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

        assertEquals("Oficina ", vm.locationInput)
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
        assertEquals("a digitação segue no campo", "Remédio da pressão", vm.titleInput)

        vm.finish() // sai da tela

        awaitStored(id) { it.title == "Remédio da pressão" }
        assertEquals("a conclusão ficou", 1, db.completionLogDao().all().count { it.taskId == id })
    }

    // --- Data, hora e repetição (Timing) --------------------------------------------------------

    @Test
    fun `duas mudancas de data e repeticao seguidas, sem esperar o banco, valem as duas`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }.timing!!

        // A3: a segunda é montada sobre o que a tela mostra, que já é a primeira (o pendente, ainda não
        // gravado). Espera a TELA mostrar a primeira (o uiState é combinação e chega depois), não o banco.
        val first = opened.copy(time = null)
        vm.updateTiming(first)
        val shown = vm.awaitUi { it.timing == first }.timing!!
        vm.updateTiming(shown.copy(repeat = ReminderRepeat.WEEKLY))
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
    fun `mudar a data e concluir logo depois - a conclusao vem depois da data e a tarefa fica encerrada`() = runBlocking {
        // NB-E2-A do Lince: o Concluir passa pela fila, depois do Timing; não reabre a tarefa.
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }
        val newDate = LocalDate.now().plusDays(3)

        vm.updateTiming(opened.timing!!.copy(date = newDate))
        vm.complete()
        vm.awaitPendingWrites()

        val logId = (awaitOffer()!!.target as UndoTarget.CompletedTask).logId
        val log = db.completionLogDao().byId(logId)!!
        // Concluída adiantada SOBRE a data nova: o registro guarda a data nova para o Desfazer.
        assertEquals(newDate.toEpochDay(), log.previousDueDate)
        assertEquals(LocalDate.now().toEpochDay(), stored(id).dueDate)

        undoOffers.undo(undoOffers.pending.value!!).join()
        awaitStored(id) { it.dueDate == newDate.toEpochDay() }
        Unit
    }

    @Test
    fun `rajada no titulo do detalhe - o campo tem cada tecla em ordem e grava o texto inteiro`() = runBlocking {
        // E3-A da Coruja: o campo lê o estado de composição do buffer (titleInput), não o uiState.
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }
        val frase = "Mercado do bairro com a lista completa da semana"

        frase.forEachIndexed { i, _ ->
            vm.updateTitle(frase.substring(0, i + 1))
            assertEquals("tecla ${i + 1}", frase.substring(0, i + 1), vm.titleInput)
        }
        passDebounce()
        vm.awaitPendingWrites()

        awaitStored(id) { it.title == frase }
        assertEquals(frase, vm.titleInput)
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
        assertFalse(vm.awaitUi { !it.canRevert }.canRevert)

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
        val id = db.taskDao().insert(Task(title = "Feira", shoppingListId = listId, createdAt = TEST_CREATED_AT))

        val state = viewModel(id).awaitUi { it.shoppingList?.progress?.total == 1 }
        assertEquals("Feira", state.shoppingList?.name)
        assertEquals(1_000L, state.shoppingList?.totalCents)
        assertEquals(listOf(ShoppingListOwner.Kind.TASK to id), state.shoppingList?.owners?.map { it.kind to it.id })
        assertFalse(state.canRevert)
    }

    @Test
    fun `Adicionar lista cria na hora com o nome da tarefa e o Reverter nao a desfaz`() = runBlocking {
        // v1.10.0 (ADR-0002 16.10.4, P46): sem seletor; o nome é o da tarefa.
        val id = db.taskDao().insert(Task(title = "Churrasco", createdAt = TEST_CREATED_AT))
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }
        vm.updateTitle("Churrasco de sábado")

        vm.createShoppingList()
        vm.awaitPendingWrites()
        val linked = checkNotNull(stored(id).shoppingListId)
        assertEquals("Churrasco de sábado", shoppingRepository.observeSummary(linked).first()?.name)

        vm.revert()
        vm.awaitPendingWrites()
        assertEquals("criar lista não entra no Reverter", linked, stored(id).shoppingListId)
    }

    @Test
    fun `excluir e adicionar lista em seguida saem na ordem - fica uma lista nova`() = runBlocking {
        // A2 da revisão da T3: excluir passa pela mesma fila do editor.
        val id = db.taskDao().insert(Task(title = "Mercado", createdAt = TEST_CREATED_AT))
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }
        vm.createShoppingList()
        vm.awaitPendingWrites()
        val first = checkNotNull(stored(id).shoppingListId)
        vm.awaitUi { it.shoppingList?.id == first }

        vm.deleteShoppingList()
        vm.createShoppingList()
        vm.awaitPendingWrites()

        val second = checkNotNull(stored(id).shoppingListId)
        assertTrue("a lista nova é outra", second != first)
        assertNull("a primeira foi excluída", shoppingRepository.list(first))
        assertEquals(listOf(second), db.shoppingListDao().all().map { it.id })
    }

    @Test
    fun `Excluir lista apaga lista e itens e tira o vinculo`() = runBlocking {
        val id = db.taskDao().insert(Task(title = "Mercado", createdAt = TEST_CREATED_AT))
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }
        vm.createShoppingList()
        vm.awaitPendingWrites()
        val listId = checkNotNull(stored(id).shoppingListId)
        shoppingRepository.add(listId, "Café", quantity = 1, priceCents = 0)
        vm.awaitUi { it.shoppingList?.id == listId }

        vm.deleteShoppingList()
        withTimeout(TIMEOUT_MS) { while (stored(id).shoppingListId != null) kotlinx.coroutines.delay(20) }

        assertNull(shoppingRepository.list(listId))
        assertTrue(db.shoppingItemDao().all().none { it.listId == listId })
    }

    @Test
    fun `vinculo orfao equivale a sem lista`() = runBlocking {
        val id = db.taskDao().insert(Task(title = "Órfã", shoppingListId = 999, createdAt = TEST_CREATED_AT))
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
        vm.finish()

        assertEquals(id, (awaitOffer()!!.target as UndoTarget.DeletedTask).task.id)
        assertNull(db.taskDao().byId(id))
    }

    @Test
    fun `digitar, excluir e fechar a tela na mesma hora - o Desfazer devolve o digitado`() = runBlocking {
        // B1: sem avançar o relógio entre os três; a exclusão é ação da fila, depois do pendente.
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Levar o carro e trocar o óleo")
        vm.delete()
        vm.finish()

        val offer = awaitOffer()!!
        assertEquals("Levar o carro e trocar o óleo", (offer.target as UndoTarget.DeletedTask).task.title)
        withContext(Dispatchers.Default) { undoOffers.undo(offer).join() }
        assertEquals("Levar o carro e trocar o óleo", awaitStored(id) { true }.title)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }

    // --- OBS-2 (decisão do Esquadro): o Reverter pula data, hora e repetição mudadas por fora ------

    @Test
    fun `OBS-2 a - mexer na data, concluir adiantado, Reverter - continua concluida com a data da conclusao`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }
        vm.updateTiming(opened.timing!!.copy(date = LocalDate.now().plusDays(3)))
        vm.awaitPendingWrites()
        taskRepository.logCompletion(id) // concluída adiantada por fora: a data vem para hoje

        vm.revert()
        vm.awaitPendingWrites()

        assertEquals(LocalDate.now().toEpochDay(), stored(id).dueDate)
        assertEquals(1, db.completionLogDao().all().count { it.taskId == id })
        Unit
    }

    @Test
    fun `OBS-2 b - mexer na data, concluir, desfazer a conclusao, Reverter - a data volta a da abertura`() = runBlocking {
        val id = tomorrowTask()
        val openedDue = stored(id).dueDate
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }
        vm.updateTiming(opened.timing!!.copy(date = LocalDate.now().plusDays(3)))
        vm.awaitPendingWrites()
        val logId = taskRepository.logCompletion(id)
        taskRepository.undoCompletion(logId) // a data volta a ser a que o editor enviou

        vm.revert()
        vm.awaitPendingWrites()

        awaitStored(id) { it.dueDate == openedDue }
        Unit
    }

    @Test
    fun `OBS-2 c - mexer na data e no titulo, concluir, Reverter - o titulo volta e a data nao`() = runBlocking {
        val id = tomorrowTask()
        val vm = viewModel(id)
        val opened = vm.awaitUi { it.timing != null }
        vm.updateTiming(opened.timing!!.copy(date = LocalDate.now().plusDays(3)))
        vm.updateTitle("Levar o carro na revisão")
        vm.flush()
        vm.awaitPendingWrites()
        taskRepository.logCompletion(id)

        vm.revert()
        vm.awaitPendingWrites()

        val after = awaitStored(id) { it.title == "Levar o carro" }
        assertEquals(LocalDate.now().toEpochDay(), after.dueDate)
        Unit
    }
}
