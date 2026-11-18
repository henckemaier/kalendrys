package com.kalendrys.app.ui.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.NewTaskList
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.tasks.TaskViewModel
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** Nova tarefa pelo Calendário com lista de compras: mesmo comportamento do painel de Tarefas. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CalendarViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var routineRepository: RoutineRepository
    private lateinit var shoppingRepository: ShoppingRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
        )
        shoppingRepository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository, NewTaskDraftStore()))

    private val day = TaskTiming(date = LocalDate.now().plusDays(1))

    @Test
    fun `nova tarefa com lista existente grava o vinculo`() = runTest {
        val listId = shoppingRepository.createList("Mercado")
        viewModel().addTask("Mercado", "", day, "", NewTaskList.Existing(listId))

        assertEquals(listId, taskRepository.tasks.first { it.isNotEmpty() }.single().shoppingListId)
        assertEquals(1, shoppingRepository.observeLists().first().size)
    }

    @Test
    fun `nova tarefa com lista nova cria a lista e vincula`() = runTest {
        viewModel().addTask("Feira", "", day, "", NewTaskList.New("Feira de domingo"))

        val task = taskRepository.tasks.first { it.isNotEmpty() }.single()
        val list = shoppingRepository.observeLists().first().single()
        assertEquals("Feira de domingo", list.name)
        assertEquals(list.id, task.shoppingListId)
    }

    @Test
    fun `nova tarefa sem lista nao cria lista`() = runTest {
        viewModel().addTask("Dentista", "", day, "")

        assertNull(taskRepository.tasks.first { it.isNotEmpty() }.single().shoppingListId)
        assertEquals(0, shoppingRepository.observeLists().first().size)
    }

    // --- Rascunho único do painel de nova tarefa (v1.7.1, E1) ------------------------------------

    @Test
    fun `rascunho comecado na aba Tarefas aparece no Calendario e vice-versa`() = runTest {
        val store = NewTaskDraftStore()
        val tasks = testDb.track(TaskViewModel(taskRepository, shoppingRepository, store, testUndoOffers(testDb, tasks = taskRepository)))
        val calendar = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository, store))
        val tapped = LocalDate.now().plusDays(3)

        tasks.openNewTask(LocalDate.now())
        tasks.updateNewTask { it.copy(title = "Pagar luz", notes = "novembro") }
        // Fechar pelo voltar não toca no store; abrir pelo Calendário encontra o que foi escrito,
        // já no dia tocado (a data não foi escolhida pelo usuário).
        calendar.openNewTask(tapped)
        val draft = calendar.newTaskDraft.value!!
        assertEquals("Pagar luz", draft.title)
        assertEquals("novembro", draft.notes)
        assertEquals(tapped, draft.timing.date)

        calendar.updateNewTask { it.withTiming(it.timing.copy(date = tapped.plusDays(1))) }
        tasks.openNewTask(LocalDate.now())
        assertEquals(tapped.plusDays(1), tasks.newTaskDraft.value!!.timing.date)
    }

    @Test
    fun `salvar grava a tarefa do rascunho e o descarta`() = runTest {
        val store = NewTaskDraftStore()
        val calendar = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository, store))
        calendar.openNewTask(day.date)
        calendar.updateNewTask { it.copy(title = "Feira", list = NewTaskList.New("Feira de domingo")) }

        calendar.saveNewTask()

        val task = taskRepository.tasks.first { it.isNotEmpty() }.single()
        assertEquals("Feira", task.title)
        assertEquals(day.date!!.toEpochDay(), task.dueDate)
        assertEquals(shoppingRepository.observeLists().first().single().id, task.shoppingListId)
        assertNull(store.draft.value)
    }

    /**
     * Rascunho único (E1): o Salvar descarta antes de gravar, então um segundo toque (toque duplo,
     * ou o painel ainda na tela) não grava outra tarefa nem cria a lista nova de novo.
     */
    @Test
    fun `salvar duas vezes seguidas grava uma tarefa so e cria a lista nova uma vez`() = runTest {
        val store = NewTaskDraftStore()
        val calendar = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository, store))
        calendar.openNewTask(day.date)
        calendar.updateNewTask { it.copy(title = "Feira", list = NewTaskList.New("Feira de domingo")) }

        calendar.saveNewTask()
        // O primeiro Salvar já descartou: o segundo não tem o que gravar.
        assertNull(store.draft.value)
        calendar.saveNewTask()
        // Marcador pelo MESMO caminho (com lista nova), gravado depois: quando ele e a lista dele
        // chegam, um segundo "Feira" lançado antes também já teria chegado.
        calendar.openNewTask(day.date)
        calendar.updateNewTask { it.copy(title = "Marcador", list = NewTaskList.New("Marca")) }
        calendar.saveNewTask()

        val tasks = taskRepository.tasks.first { list -> list.any { it.title == "Feira" } && list.any { it.title == "Marcador" } }
        val lists = shoppingRepository.observeLists().first { list -> list.any { it.name == "Marca" } }
        assertEquals(1, tasks.count { it.title == "Feira" })
        assertEquals(setOf("Feira de domingo", "Marca"), lists.map { it.name }.toSet())
        assertEquals(2, lists.size)
        assertNull(store.draft.value)
    }

    @Test
    fun `cancelar descarta o rascunho sem gravar`() = runTest {
        val store = NewTaskDraftStore()
        val tasks = testDb.track(TaskViewModel(taskRepository, shoppingRepository, store, testUndoOffers(testDb, tasks = taskRepository)))
        tasks.openNewTask(LocalDate.now())
        tasks.updateNewTask { it.copy(title = "Ligar") }

        tasks.cancelNewTask()

        assertNull(store.draft.value)
        assertTrue(db.taskDao().all().isEmpty())
    }

    @Test
    fun `lista escolhida no rascunho e excluida no meio some da escolha`() = runTest {
        val store = NewTaskDraftStore()
        val calendar = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository, store))
        val listId = shoppingRepository.createList("Mercado")
        calendar.openNewTask(day.date)
        calendar.updateNewTask { it.copy(title = "Mercado", list = NewTaskList.Existing(listId)) }

        shoppingRepository.deleteList(listId)

        // A remocao chega do Room por outra thread (executor do banco -> Flow do VM). Esperar em
        // TEMPO REAL: dentro do runTest o withTimeout usa o relogio virtual, que avanca os 5 s na
        // hora quando o agendador de teste fica ocioso, e o teste falhava quando o Room demorava
        // (intermitente no CI, run 44147716119).
        withContext(Dispatchers.Default) { withTimeout(5_000) { store.draft.first { it?.list == null } } }
        assertEquals("Mercado", store.draft.value!!.title)
    }
}
