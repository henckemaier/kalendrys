package com.kalendrys.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Oferta de Desfazer das exclusões que fecham a tela (v1.8.0: tarefa pelo detalhe, E0-D; rotina pelo
 * editor, P8; estado desde o NB-4): UMA pendente por vez, de qualquer tipo, até terminar; quem
 * restaura é o objeto (tarefa pelo restoreTask, rotina pelo unarchive).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UndoOffersTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var repository: TaskRepository
    private lateinit var routines: RoutineRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        scheduler = FakeReminderScheduler(context)
        alarms = FakeRoutineAlarms(context)
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())
        routines = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
        )
        shopping = ShoppingRepository(db)
    }

    @After
    fun tearDown() = testDb.close()

    private fun offers(scope: CoroutineScope, clock: () -> Long = { System.currentTimeMillis() }) =
        UndoOffers(repository, routines, scope, clock)

    private fun deletedTask(task: Task) = UndoTarget.DeletedTask(task)

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
        val offers = offers(backgroundScope)
        val a = repository.addTask("A")
        val b = repository.addTask("B")
        assertNull(offers.pending.value)

        offers.offer(deletedTask(a))
        val first = offers.pending.value!!
        assertEquals(deletedTask(a), first.target)
        // Ler não consome: continua pendente (girar a tela mostra de novo).
        assertEquals(first, offers.pending.value)

        offers.offer(deletedTask(b))
        val second = offers.pending.value!!
        assertEquals(deletedTask(b), second.target)
        assertNotEquals("cada oferta tem identidade própria", first.id, second.id)

        // A mensagem da antiga terminando não apaga a nova.
        offers.finish(first)
        assertEquals(second, offers.pending.value)

        offers.finish(second)
        assertNull(offers.pending.value)
    }

    @Test
    fun `a mesma tarefa oferecida duas vezes sao ofertas diferentes`() = runTest {
        val offers = offers(backgroundScope)
        val a = repository.addTask("A")

        offers.offer(deletedTask(a))
        val first = offers.pending.value!!
        offers.offer(deletedTask(a))
        val second = offers.pending.value!!

        offers.finish(first)
        assertEquals(second, offers.pending.value)
    }

    @Test
    fun `oferta de rotina substitui a de tarefa e limpar a antiga nao apaga a nova`() = runTest {
        val offers = offers(backgroundScope)
        val task = repository.addTask("A")
        val routine = routines.add(Routine(title = "Treino", startMinute = 420))

        offers.offer(deletedTask(task))
        val taskOffer = offers.pending.value!!
        offers.offer(UndoTarget.DeletedRoutine(routine.id))
        val routineOffer = offers.pending.value!!

        assertEquals(UndoTarget.DeletedRoutine(routine.id), routineOffer.target)
        assertNotEquals(taskOffer.id, routineOffer.id)
        offers.finish(taskOffer)
        assertEquals(routineOffer, offers.pending.value)
    }

    @Test
    fun `excluir mantem o historico e restaurar o reconecta, com o vinculo de lista`() = runTest {
        val offers = offers(this)
        val id = tomorrowWithList()
        val logId = repository.logCompletion(id)
        val task = repository.observeTask(id).first()!!
        val history = repository.observeCompletions(id).first()

        repository.deleteTask(task)
        assertNull(repository.observeTask(id).first())
        // Sem Desfazer a tarefa fica excluída, mas o histórico de conclusões continua lá.
        assertEquals(history, repository.observeCompletions(id).first())

        offers.offer(deletedTask(task))
        offers.undo(offers.pending.value!!).join()

        assertNull("Desfazer limpa a oferta", offers.pending.value)
        val restored = repository.observeTask(id).first()!!
        assertEquals(task, restored)
        assertEquals(task.shoppingListId, restored.shoppingListId)
        assertEquals(listOf(logId), repository.observeCompletions(id).first().map { it.id })
    }

    @Test
    fun `restaurar uma tarefa pendente reagenda o lembrete`() = runTest {
        val offers = offers(this)
        val id = tomorrowWithList()
        val task = repository.observeTask(id).first()!!
        repository.deleteTask(task)
        scheduler.reset()

        offers.offer(deletedTask(task))
        offers.undo(offers.pending.value!!).join()

        assertEquals(task, repository.observeTask(id).first())
        assertEquals(listOf(id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `Desfazer da rotina excluida a desarquiva e reagenda os alarmes`() = runTest {
        val offers = offers(this)
        val routine = routines.add(Routine(title = "Treino", startMinute = 420))
        routines.archive(routine)
        alarms.reset()

        offers.offer(UndoTarget.DeletedRoutine(routine.id))
        offers.undo(offers.pending.value!!).join()

        assertNull("Desfazer limpa a oferta", offers.pending.value)
        assertNull(testDb.db.routineDao().byId(routine.id)!!.archivedAt)
        assertTrue("desfazer reagenda", alarms.calls.isNotEmpty())
    }

    @Test
    fun `cada Desfazer restaura o seu tipo`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")
        repository.deleteTask(task)
        val routine = routines.add(Routine(title = "Treino", startMinute = 420))
        routines.archive(routine)

        offers.offer(deletedTask(task))
        offers.undo(offers.pending.value!!).join()
        offers.offer(UndoTarget.DeletedRoutine(routine.id))
        offers.undo(offers.pending.value!!).join()

        assertEquals(task, repository.observeTask(task.id).first())
        assertNull(testDb.db.routineDao().byId(routine.id)!!.archivedAt)
    }

    @Test
    fun `oferta vale o prazo a partir da criacao, para os dois tipos`() = runTest {
        var now = 1_000L
        val offers = offers(backgroundScope) { now }
        listOf(deletedTask(Task(id = 1, title = "T")), UndoTarget.DeletedRoutine(7)).forEach { target ->
            now = 1_000L
            offers.offer(target)
            val offer = offers.pending.value!!
            assertEquals(1_000L, offer.createdAt)

            now += UndoOffers.VALIDITY_MS - 1
            assertEquals(false, offers.isExpired(offer))
            now += 1
            assertEquals(true, offers.isExpired(offer))
        }
    }

    @Test
    fun `remainingMs e o que resta do prazo e nunca negativo`() = runTest {
        var now = 1_000L
        val offers = offers(backgroundScope) { now }
        offers.offer(UndoTarget.DeletedRoutine(7))
        val offer = offers.pending.value!!

        assertEquals(UndoOffers.VALIDITY_MS, offers.remainingMs(offer))
        now += 12_000
        assertEquals(UndoOffers.VALIDITY_MS - 12_000, offers.remainingMs(offer))
        now += 60 * 60 * 1_000L
        assertEquals(0L, offers.remainingMs(offer))
    }
}
