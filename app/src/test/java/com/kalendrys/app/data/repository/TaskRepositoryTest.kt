package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Sincronia banco ↔ alarme no [TaskRepository]: cada operação de dados precisa deixar o
 * [com.kalendrys.app.reminder.ReminderScheduler] no estado certo. Usa Room em memória +
 * coroutines-test + Turbine, com um fake do scheduler ([FakeReminderScheduler]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var repository: TaskRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = FakeReminderScheduler(context)
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun todayTiming(repeat: ReminderRepeat = ReminderRepeat.NONE) =
        TaskTiming(date = LocalDate.now(), time = LocalTime.of(9, 0), repeat = repeat)

    @Test
    fun `adicionar tarefa agenda o alarme`() = runTest {
        val task = repository.addTask("Comprar pão", timing = todayTiming())

        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `editar tarefa cancela o alarme antigo e reagenda`() = runTest {
        val task = repository.addTask("Tarefa", timing = todayTiming())
        scheduler.reset()

        repository.updateTask(task.copy(title = "Tarefa editada"))

        assertEquals(listOf(task.id), scheduler.cancelled)
        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `concluir tarefa sem repeticao cancela o alarme`() = runTest {
        val task = repository.addTask("Tarefa", timing = todayTiming())
        scheduler.reset()

        repository.logCompletion(task.id)

        assertEquals(listOf(task.id), scheduler.cancelled)
        assertTrue("tarefa encerrada não deve reagendar", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `concluir tarefa que se repete nao cancela o alarme`() = runTest {
        val task = repository.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        scheduler.reset()

        repository.logCompletion(task.id)

        assertTrue("tarefa recorrente mantém o alarme", scheduler.cancelled.isEmpty())
    }

    @Test
    fun `desfazer conclusao reagenda o alarme`() = runTest {
        val task = repository.addTask("Tarefa", timing = todayTiming())
        val logId = repository.logCompletion(task.id)
        scheduler.reset()

        repository.undoCompletion(logId)

        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `onReminderFired agenda a proxima ocorrencia quando a tarefa se repete`() = runTest {
        val task = repository.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        scheduler.reset()

        val fired = repository.onReminderFired(task.id, now = System.currentTimeMillis())

        assertNotNull("a notificação de hoje ainda deve aparecer", fired)
        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `taskForReminder devolve a tarefa recorrente ainda nao concluida hoje`() = runTest {
        val task = repository.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))

        assertNotNull(repository.taskForReminder(task.id))
    }

    @Test
    fun `taskForReminder e null para recorrente ja concluida hoje (idempotencia do Concluir)`() = runTest {
        val task = repository.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        repository.logCompletion(task.id)

        assertNull(
            "não pode gravar uma segunda conclusão no mesmo dia pela notificação",
            repository.taskForReminder(task.id),
        )
    }

    @Test
    fun `taskForReminder e null para one-time ja concluida`() = runTest {
        val task = repository.addTask("Pagar conta", timing = todayTiming())
        repository.logCompletion(task.id)

        assertNull(repository.taskForReminder(task.id))
    }

    @Test
    fun `taskForReminder e null para tarefa inexistente`() = runTest {
        assertNull(repository.taskForReminder(taskId = 404L))
    }

    @Test
    fun `excluir tarefa cancela o alarme e some da lista`() = runTest {
        val task = repository.addTask("Tarefa", timing = todayTiming())
        scheduler.reset()

        repository.tasks.test {
            assertEquals(listOf(task.id), awaitItem().map { it.id })
            repository.deleteTask(task)
            assertEquals(emptyList<Long>(), awaitItem().map { it.id })
        }

        assertEquals(listOf(task.id), scheduler.cancelled)
    }

    // --- Lista de compras na tarefa (ADR-0001, v1.6.0) ---

    private suspend fun listWithPickedItems(): Long {
        val shopping = ShoppingRepository(db)
        val listId = shopping.createList("Mercado")
        shopping.add(listId, "Arroz", 1, null)
        shopping.add(listId, "Feijão", 1, null)
        db.shoppingItemDao().byList(listId).forEach { shopping.setPicked(it, true) }
        return listId
    }

    private suspend fun picked(listId: Long) = db.shoppingItemDao().byList(listId).map { it.picked }

    @Test
    fun `concluir tarefa repetida zera a lista e desfazer restaura`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Feira", timing = todayTiming(ReminderRepeat.DAILY))
        repository.updateTask(task.copy(shoppingListId = listId))

        val logId = repository.logCompletion(task.id)
        assertEquals(listOf(false, false), picked(listId))

        repository.undoCompletion(logId)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `concluir tarefa unica nao mexe na lista`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Compra do mês", timing = todayTiming())
        repository.updateTask(task.copy(shoppingListId = listId))

        repository.logCompletion(task.id)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `excluir tarefa e restaurar mantem o vinculo e a lista`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Feira", timing = todayTiming())
        val linked = task.copy(shoppingListId = listId)
        repository.updateTask(linked)

        repository.deleteTask(linked)
        assertNotNull("excluir a tarefa não exclui a lista", db.shoppingListDao().byId(listId))
        repository.restoreTask(linked)
        assertEquals(listId, db.taskDao().byId(task.id)!!.shoppingListId)
    }

    @Test
    fun `vinculo orfao conclui e desfaz sem falhar`() = runTest {
        val task = repository.addTask("Órfã", timing = todayTiming(ReminderRepeat.DAILY))
        repository.updateTask(task.copy(shoppingListId = 999))

        val logId = repository.logCompletion(task.id)
        repository.undoCompletion(logId)
        assertTrue(db.completionLogDao().all().isEmpty())
    }

    @Test
    fun `tarefas vinculadas trazem o estado pendente`() = runTest {
        val listId = listWithPickedItems()
        val pending = repository.addTask("Pendente", timing = todayTiming())
        val done = repository.addTask("Feita", timing = todayTiming())
        val other = repository.addTask("Outra lista", timing = todayTiming())
        repository.updateTask(pending.copy(shoppingListId = listId))
        repository.updateTask(done.copy(shoppingListId = listId))
        repository.updateTask(other.copy(shoppingListId = listId + 1))
        repository.logCompletion(done.id)

        val linked = repository.linkedTasks(listId)
        assertEquals(
            listOf(
                com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask(pending.id, pending = true),
                com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask(done.id, pending = false),
            ),
            linked,
        )
    }
}
