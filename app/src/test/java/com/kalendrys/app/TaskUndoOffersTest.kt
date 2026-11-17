package com.kalendrys.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ofertas de Desfazer da exclusão pelo detalhe (v1.8.0, E0-D): entregues em ordem; restaurar devolve tudo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskUndoOffersTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var repository: TaskRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        scheduler = FakeReminderScheduler(context)
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())
        shopping = ShoppingRepository(db)
    }

    @After
    fun tearDown() = testDb.close()

    private suspend fun tomorrowWithList(): Long {
        val listId = shopping.createList("Feira")
        val task = repository.addTask(
            "Feira",
            timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)),
        )
        repository.updateTask(repository.observeTask(task.id).first()!!.copy(shoppingListId = listId))
        return task.id
    }

    @Test
    fun `ofertas sao entregues em ordem, cada uma uma vez`() = runTest {
        val offers = TaskUndoOffers(repository, backgroundScope)
        val a = repository.addTask("A")
        val b = repository.addTask("B")

        offers.offer(a)
        offers.offer(b)

        assertEquals(listOf(a, b), offers.offers.take(2).toList())
    }

    @Test
    fun `excluir mantem o historico e restaurar o reconecta, com o vinculo de lista`() = runTest {
        val offers = TaskUndoOffers(repository, this)
        val id = tomorrowWithList()
        val logId = repository.logCompletion(id)
        val task = repository.observeTask(id).first()!!
        val history = repository.observeCompletions(id).first()

        repository.deleteTask(task)
        assertNull(repository.observeTask(id).first())
        // Sem Desfazer a tarefa fica excluída, mas o histórico de conclusões continua lá.
        assertEquals(history, repository.observeCompletions(id).first())

        offers.restore(task).join()

        val restored = repository.observeTask(id).first()!!
        assertEquals(task, restored)
        assertEquals(task.shoppingListId, restored.shoppingListId)
        assertEquals(listOf(logId), repository.observeCompletions(id).first().map { it.id })
    }

    @Test
    fun `restaurar uma tarefa pendente reagenda o lembrete`() = runTest {
        val offers = TaskUndoOffers(repository, this)
        val id = tomorrowWithList()
        val task = repository.observeTask(id).first()!!
        repository.deleteTask(task)
        scheduler.reset()

        offers.restore(task).join()

        assertEquals(task, repository.observeTask(id).first())
        assertEquals(listOf(id), scheduler.scheduled.map { it.task.id })
    }
}
