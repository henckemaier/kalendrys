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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Oferta de Desfazer da exclusão pelo detalhe (v1.8.0, E0-D; estado desde o NB-4): pendente até terminar; Desfazer devolve tudo. */
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
    fun `oferta fica pendente ate terminar e a nova substitui a anterior`() = runTest {
        val offers = TaskUndoOffers(repository, backgroundScope)
        val a = repository.addTask("A")
        val b = repository.addTask("B")
        assertNull(offers.pending.value)

        offers.offer(a)
        val first = offers.pending.value!!
        assertEquals(a, first.task)
        // Ler não consome: continua pendente (girar a tela mostra de novo).
        assertEquals(first, offers.pending.value)

        offers.offer(b)
        val second = offers.pending.value!!
        assertEquals(b, second.task)
        assertNotEquals("cada oferta tem identidade própria", first.id, second.id)

        // A mensagem da antiga terminando não apaga a nova.
        offers.finish(first)
        assertEquals(second, offers.pending.value)

        offers.finish(second)
        assertNull(offers.pending.value)
    }

    @Test
    fun `a mesma tarefa oferecida duas vezes sao ofertas diferentes`() = runTest {
        val offers = TaskUndoOffers(repository, backgroundScope)
        val a = repository.addTask("A")

        offers.offer(a)
        val first = offers.pending.value!!
        offers.offer(a)
        val second = offers.pending.value!!

        offers.finish(first)
        assertEquals(second, offers.pending.value)
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

        offers.offer(task)
        offers.undo(offers.pending.value!!).join()

        assertNull("Desfazer limpa a oferta", offers.pending.value)
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

        offers.offer(task)
        offers.undo(offers.pending.value!!).join()

        assertEquals(task, repository.observeTask(id).first())
        assertEquals(listOf(id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `oferta vale o prazo a partir da criacao`() = runTest {
        var now = 1_000L
        val offers = TaskUndoOffers(repository, backgroundScope) { now }
        offers.offer(com.kalendrys.app.data.local.entity.Task(id = 1, title = "T"))
        val offer = offers.pending.value!!
        assertEquals(1_000L, offer.createdAt)

        now += TaskUndoOffers.VALIDITY_MS - 1
        assertEquals(false, offers.isExpired(offer))
        now += 1
        assertEquals(true, offers.isExpired(offer))
    }
}
