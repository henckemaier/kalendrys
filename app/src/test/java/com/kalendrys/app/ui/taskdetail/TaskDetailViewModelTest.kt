package com.kalendrys.app.ui.taskdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.TaskUndoOffers
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingListOwner
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lista de compras no detalhe da tarefa (ADR-0001, v1.6.0): o vínculo é do rascunho (P7 original:
 * grava com Salvar, volta com Descartar = sair sem salvar); os itens são da lista e persistem na hora.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskDetailViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository
    private lateinit var undoOffers: TaskUndoOffers

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
        undoOffers = TaskUndoOffers(taskRepository, CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(taskId: Long) = testDb.track(
        TaskDetailViewModel(SavedStateHandle(mapOf(TASK_ID_ARG to taskId)), taskRepository, shoppingRepository, undoOffers),
    )

    private suspend fun ReceiveTurbine<TaskDetailViewModel.UiState>.awaitState(
        predicate: (TaskDetailViewModel.UiState) -> Boolean,
    ): TaskDetailViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private suspend fun stored(id: Long): Task = taskRepository.observeTask(id).filterNotNull().first()

    @Test
    fun `tarefa com lista mostra o resumo sem contar como alteracao`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 2, priceCents = 500)
        val task = db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))

        viewModel(task).uiState.test {
            val state = awaitState { it.shoppingList?.progress?.total == 1 }
            assertEquals("Feira", state.shoppingList?.name)
            assertEquals(1_000L, state.shoppingList?.totalCents)
            // A própria tarefa aparece como dona (o cartão a esconde da etiqueta "Também em").
            assertEquals(listOf(ShoppingListOwner.Kind.TASK to task), state.shoppingList?.owners?.map { it.kind to it.id })
            assertFalse(state.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `usar lista existente entra no rascunho e salvar grava o vinculo`() = runTest {
        val listId = shoppingRepository.createList("Mercado")
        val task = db.taskDao().insert(Task(title = "Mercado"))

        val vm = viewModel(task)
        vm.uiState.test {
            val opened = awaitState { it.draft != null && it.availableLists.any { l -> l.id == listId } }
            assertNull(opened.shoppingList)

            vm.attachShoppingList(listId)
            val attached = awaitState { it.shoppingList?.id == listId }
            assertTrue(attached.hasChanges)
            assertTrue(attached.canSave)
            assertNull("só grava ao salvar", stored(task).shoppingListId)

            vm.save()
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listId, stored(task).shoppingListId)
    }

    @Test
    fun `criar nova anexa ao rascunho e sair sem salvar deixa a lista avulsa`() = runTest {
        val task = db.taskDao().insert(Task(title = "Churrasco"))

        val vm = viewModel(task)
        vm.uiState.test {
            awaitState { it.draft != null }
            vm.createShoppingList("Churrasco")
            val state = awaitState { it.shoppingList != null }
            assertEquals("Churrasco", state.shoppingList?.name)
            assertTrue(state.hasChanges)
            // Descartar = sair sem salvar: nada é gravado na tarefa.
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(stored(task).shoppingListId)
        val summary = shoppingRepository.observeSummaries().first().single()
        assertEquals("Churrasco", summary.name)
        assertTrue("sem dono, fica avulsa", summary.owners.isEmpty())
    }

    @Test
    fun `desvincular e salvar tira o vinculo mas mantem a lista e os itens`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 1, priceCents = null)
        val task = db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))

        val vm = viewModel(task)
        vm.uiState.test {
            awaitState { it.shoppingList != null }
            vm.detachShoppingList()
            assertTrue(awaitState { it.shoppingList == null }.hasChanges)
            vm.save()
            vm.events.first()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(stored(task).shoppingListId)
        assertEquals(listOf("Arroz"), shoppingRepository.observeItems(listId).first().map { it.name })
    }

    @Test
    fun `itens editados na lista nao contam como alteracao da tarefa`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        val task = db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))

        val vm = viewModel(task)
        vm.uiState.test {
            awaitState { it.shoppingList != null }
            shoppingRepository.add(listId, "Feijão", quantity = 1, priceCents = null)
            val state = awaitState { it.shoppingList?.progress?.total == 1 }
            assertFalse(state.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `vinculo orfao equivale a sem lista`() = runTest {
        val task = db.taskDao().insert(Task(title = "Feira", shoppingListId = 999))

        viewModel(task).uiState.test {
            val state = awaitState { it.draft != null }
            assertNull(state.shoppingList)
            assertFalse(state.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Rebase do rascunho quando a tarefa muda por fora (v1.6.0, achado 6) ---

    private suspend fun tomorrowTask(listId: Long? = null): Long =
        taskRepository.addTask(
            "Compra",
            timing = com.kalendrys.app.domain.TaskTiming(
                date = java.time.LocalDate.now().plusDays(1),
                time = java.time.LocalTime.of(9, 0),
            ),
        ).also { if (listId != null) taskRepository.updateTask(stored(it.id).copy(shoppingListId = listId)) }.id

    @Test
    fun `concluir tarefa unica de amanha pelo detalhe nao deixa alteracoes pendentes`() = runTest {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.uiState.test {
            awaitState { it.draft != null && it.task != null }
            vm.complete()
            val after = awaitState { it.task?.reminderAt == null && it.draft?.timing?.time == null }
            assertFalse(after.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concluir pela oferta da lista com o detalhe aberto nao deixa alteracoes pendentes`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        val id = tomorrowTask(listId)
        val vm = viewModel(id)
        vm.uiState.test {
            awaitState { it.draft != null && it.task?.shoppingListId == listId }
            // A oferta da tela da lista chama o mesmo logCompletion, por fora do detalhe.
            taskRepository.logCompletion(id)
            val after = awaitState { it.task?.reminderAt == null && it.draft?.timing?.time == null }
            assertFalse(after.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salvar depois de editar so o titulo nao regrava a data antiga`() = runTest {
        val id = tomorrowTask()
        val vm = viewModel(id)
        vm.uiState.test {
            awaitState { it.draft != null }
            taskRepository.logCompletion(id) // muda data e lembrete por fora
            awaitState { it.task?.reminderAt == null && it.draft?.timing?.time == null }
            vm.updateTitle("Compra do mês")
            awaitState { it.canSave }
            vm.save()
            awaitState { it.task?.title == "Compra do mês" }
            cancelAndIgnoreRemainingEvents()
        }
        val saved = stored(id)
        assertEquals("Compra do mês", saved.title)
        assertEquals(java.time.LocalDate.now().toEpochDay(), saved.dueDate)
        assertNull(saved.reminderAt)
    }

    @Test
    fun `lista excluida por fora zera o vinculo do rascunho sem alteracao pendente`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        val id = tomorrowTask(listId)
        val vm = viewModel(id)
        vm.uiState.test {
            awaitState { it.draft?.shoppingListId == listId }
            shoppingRepository.deleteList(listId)
            val after = awaitState { it.task?.shoppingListId == null && it.draft?.shoppingListId == null }
            assertFalse(after.hasChanges)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Excluir pelo detalhe com Desfazer na origem (v1.8.0, E0-D) -------------------------------

    @Test
    fun `excluir apaga, oferece o Desfazer com a tarefa como estava e so entao fecha`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        val id = tomorrowTask(listId)
        val before = stored(id)
        val vm = viewModel(id)
        vm.uiState.test {
            awaitState { it.task != null }
            vm.delete()
            assertEquals(TaskDetailViewModel.Event.Deleted, vm.events.first())
            cancelAndIgnoreRemainingEvents()
        }

        // Quando o fechar chega, a oferta já está no canal (sem esperar), com lembrete e vínculo de lista.
        val offered = withTimeout(1_000) { undoOffers.offers.first() }
        assertEquals(before, offered)
        assertEquals(listId, offered.shoppingListId)
        assertTrue(offered.reminderAt != null)
        assertNull(db.taskDao().byId(id))
    }
}
