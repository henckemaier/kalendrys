package com.kalendrys.app.ui.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.tasks.NewTaskList
import java.time.LocalDate
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
        )
        shoppingRepository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(CalendarViewModel(taskRepository, routineRepository, shoppingRepository))

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
}
