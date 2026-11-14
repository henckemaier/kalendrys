package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.domain.ShoppingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.kalendrys.app.testutil.ViewModelTestDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tela de uso da lista (ADR-0001, fatia 2). Room em memória + coroutines-test + Turbine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingListViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var repository: ShoppingRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var scheduler: FakeReminderScheduler

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        repository = ShoppingRepository(db)
        scheduler = FakeReminderScheduler(context)
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())
    }

    @After
    fun tearDown() {
        testDb.close() // cancela os ViewModels e drena o Room antes de fechar e do resetMain
        Dispatchers.resetMain()
    }

    private fun viewModel(listId: Long, taskId: Long? = null): ShoppingListViewModel {
        val args = buildMap<String, Any> {
            put(SHOPPING_LIST_ID_ARG, listId)
            if (taskId != null) put(SHOPPING_LIST_TASK_ID_ARG, taskId)
        }
        return testDb.track(ShoppingListViewModel(SavedStateHandle(args), repository, taskRepository))
    }

    private suspend fun ReceiveTurbine<ShoppingListViewModel.UiState>.awaitUntil(
        predicate: (ShoppingListViewModel.UiState) -> Boolean,
    ): ShoppingListViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    @Test
    fun `carrega lista e itens com progresso e total`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 2, priceCents = 1_000)
        repository.add(listId, "Feijão", quantity = 1, priceCents = null)

        val vm = viewModel(listId)
        vm.uiState.test {
            val state = awaitUntil { it.items.size == 2 }
            assertEquals("Mercado", state.list?.name)
            assertFalse(state.notFound)
            assertEquals(listOf("Arroz", "Feijão"), state.items.map { it.name })
            assertEquals(ShoppingProgress(picked = 0, total = 2), state.progress)
            assertEquals(2_000L, state.totalCents)
            assertFalse(state.hasPicked)
        }
    }

    @Test
    fun `lista inexistente vira notFound sem itens`() = runTest {
        val vm = viewModel(listId = 999)
        vm.uiState.test {
            val state = awaitUntil { it.isLoaded }
            assertTrue(state.notFound)
            assertNull(state.list)
            assertTrue(state.items.isEmpty())
        }
    }

    @Test
    fun `lista excluida enquanto aberta vira notFound`() = runTest {
        val listId = repository.createList("Feira")
        val vm = viewModel(listId)
        vm.uiState.test {
            awaitUntil { it.list != null }
            repository.deleteList(listId)
            assertTrue(awaitUntil { it.notFound }.notFound)
        }
    }

    @Test
    fun `adicionar, editar e marcar persistem na hora`() = runTest {
        val listId = repository.createList("Mercado")
        val vm = viewModel(listId)
        vm.uiState.test {
            awaitUntil { it.list != null }

            vm.addItem("Leite", quantity = 3, priceCents = 500)
            val added = awaitUntil { it.items.size == 1 }.items.single()
            assertEquals(3, added.quantity)

            vm.updateItem(added, name = "Leite integral", quantity = 2, priceCents = 650)
            val edited = awaitUntil { it.items.single().name == "Leite integral" }
            assertEquals(1_300L, edited.totalCents)

            vm.togglePicked(edited.items.single())
            val picked = awaitUntil { it.items.single().picked }
            assertEquals(ShoppingProgress(picked = 1, total = 1), picked.progress)
            assertTrue(picked.progress.isComplete)

            vm.togglePicked(picked.items.single())
            assertFalse(awaitUntil { !it.items.single().picked }.hasPicked)
        }
        assertEquals("Leite integral", repository.observeItems(listId).first().single().name)
    }

    @Test
    fun `nome em branco nao adiciona item`() = runTest {
        val listId = repository.createList("Mercado")
        val vm = viewModel(listId)
        vm.uiState.test {
            awaitUntil { it.list != null }
            vm.addItem("   ", quantity = 1, priceCents = null)
            expectNoEvents()
        }
        assertTrue(repository.observeItems(listId).first().isEmpty())
    }

    @Test
    fun `remover emite evento e desfazer reinsere com o mesmo id e marcacao`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 1, priceCents = null)
        repository.add(listId, "Café", quantity = 1, priceCents = 1_800)
        val cafe = repository.observeItems(listId).first().first { it.name == "Café" }
        repository.setPicked(cafe, true)

        val vm = viewModel(listId)
        val picked = vm.uiState.first { it.items.size == 2 }.items.first { it.name == "Café" }
        vm.events.test {
            vm.removeItem(picked)
            val event = awaitItem() as ShoppingListViewModel.Event.ItemRemoved
            assertEquals(picked, event.item)
            vm.undoRemove(event.item)
        }

        val items = vm.uiState.first { it.items.size == 2 }.items
        val restored = items.first { it.name == "Café" }
        assertEquals(picked.id, restored.id)
        assertEquals(picked.position, restored.position)
        assertTrue(restored.picked)
        assertEquals(listOf("Arroz", "Café"), items.map { it.name })
    }

    @Test
    fun `desmarcar todos zera as marcacoes`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 1, priceCents = null)
        repository.add(listId, "Feijão", quantity = 1, priceCents = null)
        repository.observeItems(listId).first().forEach { repository.setPicked(it, true) }

        val vm = viewModel(listId)
        vm.uiState.test {
            assertTrue(awaitUntil { it.progress.picked == 2 }.hasPicked)
            vm.clearPicked()
            val cleared = awaitUntil { it.progress.picked == 0 }
            assertEquals(2, cleared.progress.total)
            assertFalse(cleared.hasPicked)
        }
    }

    @Test
    fun `desmarcar todos emite evento e desfazer devolve so o que estava marcado`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 1, priceCents = null)
        repository.add(listId, "Feijão", quantity = 1, priceCents = null)
        repository.add(listId, "Sal", quantity = 1, priceCents = null)
        repository.observeItems(listId).first().filter { it.name != "Sal" }.forEach { repository.setPicked(it, true) }

        val vm = viewModel(listId)
        vm.uiState.first { it.progress.picked == 2 }
        vm.events.test {
            vm.clearPicked()
            val event = awaitItem() as ShoppingListViewModel.Event.PickedCleared
            assertEquals(2, event.itemIds.size)
            vm.undoClearPicked(event.itemIds)
        }

        val items = vm.uiState.first { it.progress.picked == 2 }.items
        assertEquals(mapOf("Arroz" to true, "Feijão" to true, "Sal" to false), items.associate { it.name to it.picked })
    }

    @Test
    fun `desmarcar todos sem nada marcado nao emite evento`() = runTest {
        val listId = repository.createList("Mercado")
        repository.add(listId, "Arroz", quantity = 1, priceCents = null)

        val vm = viewModel(listId)
        vm.uiState.first { it.items.size == 1 }
        vm.events.test {
            vm.clearPicked()
            expectNoEvents()
        }
    }

    @Test
    fun `renomear troca o nome e ignora nome em branco`() = runTest {
        val listId = repository.createList("Mercado")
        val vm = viewModel(listId)
        vm.uiState.test {
            awaitUntil { it.list != null }
            vm.rename("  ")
            vm.rename("  Atacado  ")
            assertEquals("Atacado", awaitUntil { it.list?.name != "Mercado" }.list?.name)
        }
    }

    @Test
    fun `taskId da rota e opcional`() {
        assertNull(viewModel(listId = 1).originTaskId)
        assertNull(viewModel(listId = 1, taskId = NO_TASK_ID).originTaskId)
        assertEquals(42L, viewModel(listId = 1, taskId = 42).originTaskId)
    }

    @Test
    fun `rota da lista com e sem tarefa`() {
        assertEquals("shopping-list/7", shoppingListRoute(7))
        assertEquals("shopping-list/7?taskId=3", shoppingListRoute(7, taskId = 3))
    }

    // --- Oferta "Concluir tarefa" (P3, v1.6.0) ------------------------------------------------------

    /** Lista com [names] itens; marca todos menos o último e devolve o id da lista. */
    private suspend fun listMissingOne(vararg names: String): Long {
        val listId = repository.createList("Mercado")
        names.forEach { repository.add(listId, it, quantity = 1, priceCents = null) }
        repository.observeItems(listId).first().dropLast(1).forEach { repository.setPicked(it, true) }
        return listId
    }

    private suspend fun lastUnpicked(listId: Long) = repository.observeItems(listId).first().single { !it.picked }

    @Test
    fun `marcar o ultimo item oferece concluir a unica tarefa pendente vinculada`() = runTest {
        val listId = listMissingOne("Arroz", "Feijão")
        val taskId = db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))

        val vm = viewModel(listId)
        vm.events.test {
            vm.togglePicked(lastUnpicked(listId))
            assertEquals(ShoppingListViewModel.Event.OfferCompleteTask(taskId, "Ir ao mercado"), awaitItem())
        }
    }

    @Test
    fun `tarefa de origem tem prioridade quando ha mais de uma pendente`() = runTest {
        val listId = listMissingOne("Arroz", "Feijão")
        db.taskDao().insert(Task(title = "Feira", shoppingListId = listId))
        val origin = db.taskDao().insert(Task(title = "Mercado do mês", shoppingListId = listId))

        val vm = viewModel(listId, taskId = origin)
        vm.events.test {
            vm.togglePicked(lastUnpicked(listId))
            assertEquals(ShoppingListViewModel.Event.OfferCompleteTask(origin, "Mercado do mês"), awaitItem())
        }
    }

    @Test
    fun `sem oferta com duas pendentes e sem origem, sem tarefa ou com lista incompleta`() = runTest {
        // Duas pendentes, sem origem: ambígua.
        val twoTasks = listMissingOne("Arroz", "Feijão")
        db.taskDao().insert(Task(title = "Feira", shoppingListId = twoTasks))
        db.taskDao().insert(Task(title = "Mercado", shoppingListId = twoTasks))
        // Só rotina vinculada: rotinas não recebem oferta.
        val routineOnly = listMissingOne("Leite")
        db.routineDao().insert(Routine(title = "Compras", startMinute = 540, shoppingListId = routineOnly))
        // Lista que continua incompleta depois da marcação (2 de 3 desmarcados; marca 1).
        val incomplete = repository.createList("Padaria")
        listOf("Café", "Açúcar", "Sal").forEach { repository.add(incomplete, it, quantity = 1, priceCents = null) }
        db.taskDao().insert(Task(title = "Padaria", shoppingListId = incomplete))
        val firstUnpicked = repository.observeItems(incomplete).first().first { !it.picked }

        val vms = listOf(viewModel(twoTasks), viewModel(routineOnly), viewModel(incomplete))
        vms.zip(listOf(lastUnpicked(twoTasks), lastUnpicked(routineOnly), firstUnpicked)).forEach { (vm, item) ->
            vm.events.test {
                vm.togglePicked(item).join()
                expectNoEvents()
            }
        }
    }

    @Test
    fun `desmarcar nunca oferece`() = runTest {
        val listId = listMissingOne("Arroz")
        db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))
        repository.setPicked(lastUnpicked(listId), true)
        val picked = repository.observeItems(listId).first().single()

        val vm = viewModel(listId)
        vm.events.test {
            vm.togglePicked(picked).join()
            expectNoEvents()
        }
    }

    @Test
    fun `aceitar a oferta conclui a tarefa e desfazer remove a conclusao`() = runTest {
        val listId = listMissingOne("Arroz")
        val taskId = db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))

        val vm = viewModel(listId)
        vm.events.test {
            vm.togglePicked(lastUnpicked(listId))
            val offer = awaitItem() as ShoppingListViewModel.Event.OfferCompleteTask
            vm.completeTask(offer.taskId)
            val completed = awaitItem() as ShoppingListViewModel.Event.TaskCompleted
            assertEquals(listOf(completed.logId), db.completionLogDao().observeForTask(taskId).first().map { it.id })
            // Concluir pela oferta tira da tela o lembrete já exibido dessa tarefa.
            assertEquals(listOf(taskId), scheduler.dismissed)

            vm.undoTaskCompletion(completed.logId)
        }
        // O Desfazer apaga o CompletionLog (espera o banco refletir).
        assertTrue(db.completionLogDao().observeForTask(taskId).first { it.isEmpty() }.isEmpty())
        // Tarefa única: concluir não reinicia as marcações (P4).
        assertTrue(repository.observeItems(listId).first().all { it.picked })
    }

    @Test
    fun `abrir a lista pela tarefa nao dispensa o lembrete`() = runTest {
        val listId = listMissingOne("Arroz")
        val taskId = db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))

        val vm = viewModel(listId, taskId)
        vm.uiState.first { it.items.isNotEmpty() }
        assertTrue("só abrir a lista (ação Abrir lista) mantém a notificação", scheduler.dismissed.isEmpty())
    }

    @Test
    fun `tarefa ja concluida nao recebe oferta`() = runTest {
        val listId = listMissingOne("Arroz")
        val taskId = db.taskDao().insert(Task(title = "Ir ao mercado", shoppingListId = listId))
        taskRepository.logCompletion(taskId)

        val vm = viewModel(listId)
        vm.events.test {
            vm.togglePicked(lastUnpicked(listId)).join()
            expectNoEvents()
        }
    }
}
