package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lista de compras na lista de tarefas (ADR-0001, v1.6.0): progresso 3/8 na linha e anexar ao criar.
 * Desfazer de concluir e excluir pela lista é a oferta do app (v1.8.0, E5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository
    private lateinit var undoOffers: UndoOffers

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
        undoOffers = testUndoOffers(testDb, tasks = taskRepository)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(TaskViewModel(taskRepository, shoppingRepository, undoOffers))

    private suspend fun ReceiveTurbine<TaskViewModel.UiState>.awaitState(
        predicate: (TaskViewModel.UiState) -> Boolean,
    ): TaskViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private fun TaskViewModel.UiState.item(title: String): TaskViewModel.Item =
        (overdue + dueToday + upcoming + noDate + doneToday).single { it.task.title == title }

    @Test
    fun `linha da tarefa leva o progresso da lista e acompanha as marcacoes`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 1, priceCents = null)
        shoppingRepository.add(listId, "Feijão", quantity = 1, priceCents = null)
        db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))

        viewModel().uiState.test {
            awaitState { it.isLoaded && it.item("Feira").listProgress == ShoppingProgress(0, 2) }
            val arroz = shoppingRepository.observeItems(listId).first().first { it.name == "Arroz" }
            shoppingRepository.setPicked(arroz, picked = true)
            awaitState { it.item("Feira").listProgress == ShoppingProgress(1, 2) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sem lista, lista vazia ou vinculo orfao nao mostram progresso`() = runTest {
        val empty = shoppingRepository.createList("Vazia")
        db.taskDao().insert(Task(title = "Sem lista"))
        db.taskDao().insert(Task(title = "Lista vazia", shoppingListId = empty))
        db.taskDao().insert(Task(title = "Órfã", shoppingListId = 999))

        viewModel().uiState.test {
            val state = awaitState { it.isLoaded && it.noDate.size == 3 }
            assertNull(state.item("Sem lista").listProgress)
            assertNull(state.item("Lista vazia").listProgress)
            assertNull(state.item("Órfã").listProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- E5 (v1.8.0): Desfazer da lista é a oferta do app ---

    /** A oferta do app publicada pela ação (no escopo do app, tempo real). */
    private suspend fun awaitOffer() = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5_000) { undoOffers.pending.first { it != null } }
    }

    @Test
    fun `concluir pelo circulo publica a oferta do app e o Desfazer apaga o registro e a tarefa volta a pendente`() = runTest {
        val task = taskRepository.addTask("Pagar conta")
        val vm = viewModel()

        vm.complete(task)

        val offer = awaitOffer()!!
        val logId = (offer.target as UndoTarget.CompletedTask).logId
        assertEquals(task.id, db.completionLogDao().byId(logId)?.taskId)
        vm.uiState.test {
            awaitState { it.isLoaded && it.doneToday.any { item -> item.task.id == task.id } }
            undoOffers.undo(offer).join()
            awaitState { it.noDate.any { item -> item.task.id == task.id } && it.doneToday.isEmpty() }
            cancelAndIgnoreRemainingEvents()
        }
        assertNull("registro apagado", db.completionLogDao().byId(logId))
        assertNull(undoOffers.pending.value)
    }

    @Test
    fun `excluir pelo deslize publica a oferta do app e o Desfazer devolve a tarefa`() = runTest {
        val task = taskRepository.addTask("Pagar conta")

        viewModel().delete(task)

        val offer = awaitOffer()!!
        assertEquals(UndoTarget.DeletedTask(task), offer.target)
        assertNull(db.taskDao().byId(task.id))
        undoOffers.undo(offer).join()
        assertEquals(task, db.taskDao().byId(task.id))
    }

    @Test
    fun `escopo da tela cancelado logo depois de concluir ainda conclui e publica a oferta`() = runTest {
        val task = taskRepository.addTask("Pagar conta")
        val vm = viewModel()

        vm.complete(task)
        vm.viewModelScope.cancel()

        val logId = (awaitOffer()!!.target as UndoTarget.CompletedTask).logId
        assertEquals(task.id, db.completionLogDao().byId(logId)?.taskId)
    }

    @Test
    fun `escopo da tela cancelado logo depois de excluir ainda exclui e publica a oferta`() = runTest {
        val task = taskRepository.addTask("Pagar conta")
        val vm = viewModel()

        vm.delete(task)
        vm.viewModelScope.cancel()

        assertEquals(UndoTarget.DeletedTask(task), awaitOffer()!!.target)
        assertNull(db.taskDao().byId(task.id))
    }

    @Test
    fun `excluir e depois concluir outra pela lista - a conclusao substitui e o Desfazer desfaz so ela`() = runTest {
        val deleted = taskRepository.addTask("Excluída")
        val done = taskRepository.addTask("Concluída")
        val vm = viewModel()

        vm.delete(deleted)
        val deleteOffer = awaitOffer()!!
        vm.complete(done)
        val completeOffer = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { undoOffers.pending.first { it != null && it != deleteOffer } }
        }!!
        val logId = (completeOffer.target as UndoTarget.CompletedTask).logId

        undoOffers.undo(completeOffer).join()

        assertNull("só a conclusão é desfeita", db.completionLogDao().byId(logId))
        assertNull("a excluída continua excluída", db.taskDao().byId(deleted.id))
        assertTrue(db.taskDao().byId(done.id) != null)
        assertNull(undoOffers.pending.value)
    }
}
