package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.NewTaskList
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lista de compras na lista de tarefas (ADR-0001, v1.6.0): progresso 3/8 na linha e anexar ao criar. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(TaskViewModel(taskRepository, shoppingRepository, NewTaskDraftStore()))

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

    @Test
    fun `criar tarefa com lista existente grava o vinculo`() = runTest {
        val listId = shoppingRepository.createList("Mercado")
        viewModel().addTask("Mercado", "", TaskTiming(date = null), "", NewTaskList.Existing(listId))

        val task = taskRepository.tasks.first { it.isNotEmpty() }.single()
        assertEquals(listId, task.shoppingListId)
        assertEquals(1, shoppingRepository.observeLists().first().size)
    }

    @Test
    fun `criar tarefa com lista nova cria a lista so ao salvar e vincula`() = runTest {
        viewModel().addTask("Churrasco", "", TaskTiming(date = null), "", NewTaskList.New("Churrasco de sábado"))

        val task = taskRepository.tasks.first { it.isNotEmpty() }.single()
        val list = shoppingRepository.observeLists().first().single()
        assertEquals("Churrasco de sábado", list.name)
        assertEquals(list.id, task.shoppingListId)
    }

    @Test
    fun `criar tarefa sem lista nao cria lista`() = runTest {
        viewModel().addTask("Ligar", "", TaskTiming(date = null), "")

        assertNull(taskRepository.tasks.first { it.isNotEmpty() }.single().shoppingListId)
        assertEquals(0, shoppingRepository.observeLists().first().size)
    }
}
