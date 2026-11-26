package com.kalendrys.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.deletedTaskTarget
import com.kalendrys.app.testutil.linkList
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.clockOf
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * Oferta de Desfazer do app (v1.8.0: tarefa pelo detalhe, E0-D; rotina pelo editor, P8; excluir e
 * concluir pela lista de Tarefas, E5; estado desde o NB-4): UMA pendente por vez, de qualquer tipo,
 * até terminar; quem desfaz é o objeto (restoreTask, unarchive ou undoCompletion pelo registro).
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
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock)
        routines = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        shopping = ShoppingRepository(db, clock = SystemAppClock)
    }

    @After
    fun tearDown() = testDb.close()

    private fun offers(scope: CoroutineScope, clock: () -> Long = { System.currentTimeMillis() }) =
        UndoOffers(repository, routines, scope, clockOf(now = clock))

    private fun deletedTask(task: Task) = deletedTaskTarget(task)

    private suspend fun tomorrowWithList(): Long {
        val listId = shopping.createList("Feira")
        val task = repository.addTask(
            "Feira",
            timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)),
        )
        testDb.db.linkList(task.id, listId)
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
        val routine = routines.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT))

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
    fun `excluir mantem o historico e restaurar o reconecta, com a lista e os itens`() = runTest {
        val offers = offers(this)
        val id = tomorrowWithList()
        val logId = repository.logCompletion(id)
        val task = repository.observeTask(id).first()!!
        val listId = checkNotNull(task.shoppingListId)
        val list = testDb.db.shoppingListDao().byId(listId)!!
        shopping.add(listId, "Arroz", quantity = 1, priceCents = null)
        val items = testDb.db.shoppingItemDao().all().filter { it.listId == listId }
        val history = repository.observeCompletions(id).first()

        offers.deleteTask(task).join()
        assertNull(repository.observeTask(id).first())
        // v1.10.0: a lista e os itens saem com a tarefa; o histórico de conclusões continua lá.
        assertNull(testDb.db.shoppingListDao().byId(listId))
        assertEquals(history, repository.observeCompletions(id).first())

        offers.undo(offers.pending.value!!).join()

        assertNull("Desfazer limpa a oferta", offers.pending.value)
        assertEquals(task, repository.observeTask(id).first())
        assertEquals("a lista volta com o mesmo id", list, testDb.db.shoppingListDao().byId(listId))
        assertEquals("e os itens também", items, testDb.db.shoppingItemDao().all().filter { it.listId == listId })
        assertEquals(listOf(logId), repository.observeCompletions(id).first().map { it.id })
    }

    @Test
    fun `excluir tarefa que ja nao existe nao oferece Desfazer`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Feira")
        repository.deleteTask(task)

        offers.deleteTask(task).join()

        assertNull(offers.pending.value)
    }

    @Test
    fun `restaurar uma tarefa pendente reagenda o lembrete`() = runTest {
        val offers = offers(this)
        val id = tomorrowWithList()
        val task = repository.observeTask(id).first()!!
        val deleted = checkNotNull(repository.deleteTask(task))
        scheduler.reset()

        offers.offer(UndoTarget.DeletedTask(deleted))
        offers.undo(offers.pending.value!!).join()

        assertEquals(task, repository.observeTask(id).first())
        assertEquals(listOf(id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `Desfazer da rotina excluida a desarquiva e reagenda os alarmes`() = runTest {
        val offers = offers(this)
        val routine = routines.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT))
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
        val routine = routines.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT))
        routines.archive(routine)

        offers.offer(deletedTask(task))
        offers.undo(offers.pending.value!!).join()
        offers.offer(UndoTarget.DeletedRoutine(routine.id))
        offers.undo(offers.pending.value!!).join()

        assertEquals(task.id, repository.observeTask(task.id).first()?.id)
        assertNull(testDb.db.routineDao().byId(routine.id)!!.archivedAt)
    }

    @Test
    fun `oferta vale o prazo a partir da criacao, para os dois tipos`() = runTest {
        var now = 1_000L
        val offers = offers(backgroundScope) { now }
        listOf(deletedTask(Task(id = 1, title = "T", createdAt = TEST_CREATED_AT)), UndoTarget.DeletedRoutine(7)).forEach { target ->
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

    // --- NB-E3-C: excluir e oferecer é uma operação só, no escopo do app ---

    /** Pede [request] de dentro de um escopo de "tela" e o cancela na hora, como fechar a tela. */
    private fun fromScreenThenClose(request: () -> Job): Job {
        val screen = CoroutineScope(Job())
        var job: Job? = null
        screen.launch(start = CoroutineStart.UNDISPATCHED) { job = request() }
        screen.cancel()
        return job!!
    }

    @Test
    fun `excluir tarefa e fechar a tela na hora ainda exclui e publica a oferta`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")

        fromScreenThenClose { offers.deleteTask(task) }.join()

        assertNull(repository.observeTask(task.id).first())
        assertEquals(deletedTaskTarget(task), offers.pending.value?.target)
    }

    @Test
    fun `excluir rotina e fechar a tela na hora ainda arquiva e publica a oferta`() = runTest {
        val offers = offers(this)
        val routine = routines.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT))

        fromScreenThenClose { offers.deleteRoutine(routine) }.join()

        assertTrue(testDb.db.routineDao().byId(routine.id)!!.archivedAt != null)
        assertEquals(UndoTarget.DeletedRoutine(routine.id), offers.pending.value?.target)
    }

    @Test
    fun `Desfazer e sair na hora ainda restaura os dois tipos`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")
        offers.deleteTask(task).join()
        val taskOffer = offers.pending.value!!
        fromScreenThenClose { offers.undo(taskOffer) }.join()
        assertEquals(task.id, repository.observeTask(task.id).first()?.id)

        val routine = routines.add(Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT))
        offers.deleteRoutine(routine).join()
        val routineOffer = offers.pending.value!!
        fromScreenThenClose { offers.undo(routineOffer) }.join()
        assertNull(testDb.db.routineDao().byId(routine.id)!!.archivedAt)
        assertNull(offers.pending.value)
    }

    // --- E5: concluir pela lista de Tarefas também é uma operação do app ---

    @Test
    fun `concluir publica a oferta e o Desfazer apaga o registro e deixa a tarefa pendente`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")

        offers.completeTask(task).join()
        val offer = offers.pending.value!!
        val logId = (offer.target as UndoTarget.CompletedTask).logId
        assertEquals(task.id, testDb.db.completionLogDao().byId(logId)?.taskId)

        offers.undo(offer).join()

        assertNull("registro apagado", testDb.db.completionLogDao().byId(logId))
        assertTrue("tarefa pendente", repository.observeCompletions(task.id).first().isEmpty())
        assertEquals(task.id, repository.observeTask(task.id).first()?.id)
        assertNull(offers.pending.value)
    }

    @Test
    fun `concluir e fechar a tela na hora ainda conclui e publica a oferta`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")

        fromScreenThenClose { offers.completeTask(task) }.join()

        val logId = (offers.pending.value?.target as UndoTarget.CompletedTask).logId
        assertEquals(task.id, testDb.db.completionLogDao().byId(logId)?.taskId)
    }

    @Test
    fun `exclusao seguida de conclusao - a segunda substitui e o Desfazer dela desfaz so a conclusao`() = runTest {
        val offers = offers(this)
        val deleted = repository.addTask("Excluída")
        val done = repository.addTask("Concluída")

        offers.deleteTask(deleted).join()
        val deleteOffer = offers.pending.value!!
        offers.completeTask(done).join()
        val completeOffer = offers.pending.value!!
        assertNotEquals(deleteOffer.id, completeOffer.id)
        val logId = (completeOffer.target as UndoTarget.CompletedTask).logId

        offers.undo(completeOffer).join()

        assertNull("só a conclusão é desfeita", testDb.db.completionLogDao().byId(logId))
        assertNull("a excluída continua excluída", repository.observeTask(deleted.id).first())
        assertNull(offers.pending.value)
        // A antiga, substituída, terminando depois não faz nada.
        offers.finish(deleteOffer)
        assertNull(repository.observeTask(deleted.id).first())
    }

    // --- v1.9.0, E4: concluir e pular pela Hoje ----------------------------------------------------

    @Test
    fun `concluir rotina oferece e o Desfazer desfaz a conclusao do dia`() = runTest {
        val offers = offers(this)
        val today = LocalDate.now()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))

        offers.completeRoutine(routine.id, today, today).join()

        assertEquals(UndoTarget.CompletedRoutine(routine.id, today.toEpochDay()), offers.pending.value?.target)
        assertTrue(routine.id in testDb.db.routineCompletionDao().doneOn(today.toEpochDay()))
        offers.undo(offers.pending.value!!).join()
        assertTrue(routine.id !in testDb.db.routineCompletionDao().doneOn(today.toEpochDay()))
    }

    @Test
    fun `concluir rotina ja concluida nao oferece`() = runTest {
        // Um segundo toque (widget e Hoje juntos) não gera um Desfazer que apagaria a conclusão do primeiro.
        val offers = offers(this)
        val today = LocalDate.now()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        routines.complete(routine.id, today, today)

        offers.completeRoutine(routine.id, today, today).join()

        assertNull(offers.pending.value)
    }

    @Test
    fun `A1 - desmarcar direto descarta a oferta e a conclusao do widget depois fica intacta`() = runTest {
        // Concluir pela Hoje (oferta), desmarcar no bloco (direto), concluir pelo widget (sem oferta):
        // não sobra mensagem com Desfazer para apagar a conclusão do widget.
        val offers = offers(this)
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))

        offers.completeRoutine(routine.id, today, today).join()
        offers.discardRoutineCompletion(routine.id, epochDay)
        routines.uncomplete(routine.id, today)
        routines.complete(routine.id, today, today) // o widget

        assertNull("sem mensagem na tela", offers.pending.value)
        assertTrue(routine.id in testDb.db.routineCompletionDao().doneOn(epochDay))
    }

    @Test
    fun `descartar so tira a oferta do mesmo bloco e dia`() = runTest {
        val offers = offers(this)
        val today = LocalDate.now()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        offers.completeRoutine(routine.id, today, today).join()

        offers.discardRoutineCompletion(routine.id, today.minusDays(1).toEpochDay())
        offers.discardRoutineCompletion(routine.id + 1, today.toEpochDay())

        assertEquals(UndoTarget.CompletedRoutine(routine.id, today.toEpochDay()), offers.pending.value?.target)
    }

    @Test
    fun `pular oferece com a excecao anterior e o Desfazer a devolve`() = runTest {
        val offers = offers(this)
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        routines.rescheduleOccurrence(routine.id, epochDay, newStartMinute = 930, newEpochDay = null)

        offers.skipRoutine(routine.id, today).join()

        val target = offers.pending.value?.target as UndoTarget.SkippedRoutine
        assertEquals(930, target.previous?.newStartMinute)
        offers.undo(offers.pending.value!!).join()
        assertEquals(930, routines.occurrenceOverride(routine.id, epochDay)?.newStartMinute)
    }

    @Test
    fun `pular rotina que nao existe mais nao oferece Desfazer`() = runTest {
        // N2 da E4 da v1.9.0 (v1.10.0, E0): a oferta cujo Desfazer não faria nada não aparece.
        val offers = offers(this)

        offers.skipRoutine(999, LocalDate.now()).join()

        assertNull(offers.pending.value)
    }

    @Test
    fun `pular rotina arquivada nao oferece Desfazer`() = runTest {
        val offers = offers(this)
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        routines.archive(routine)

        offers.skipRoutine(routine.id, LocalDate.now()).join()

        assertNull(offers.pending.value)
    }

    @Test
    fun `pular rotina que nao existe mais nao derruba a oferta pendente`() = runTest {
        val offers = offers(this)
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        offers.skipRoutine(routine.id, LocalDate.now()).join()
        val pending = offers.pending.value

        offers.skipRoutine(999, LocalDate.now()).join()

        assertEquals(pending, offers.pending.value)
    }

    @Test
    fun `desfazer pular do bloco descarta a oferta do pular`() = runTest {
        val offers = offers(this)
        val today = LocalDate.now()
        val routine = routines.add(Routine(title = "Treino", startMinute = 600, createdAt = TEST_CREATED_AT))
        offers.skipRoutine(routine.id, today).join()

        offers.discardRoutineSkip(routine.id, today.toEpochDay())

        assertNull(offers.pending.value)
    }

    @Test
    fun `concluir tarefa num dia passado grava naquele dia e o Desfazer apaga esse registro`() = runTest {
        // A Hoje passa o instante do dia mostrado (conclusão retroativa, 14.4).
        val offers = offers(this)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = LocalDate.now().minusDays(3)))
        val yesterday = LocalDate.now().minusDays(1).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        offers.completeTask(task, completedAt = yesterday, now = System.currentTimeMillis()).join()

        val logId = (offers.pending.value?.target as UndoTarget.CompletedTask).logId
        assertEquals(yesterday, testDb.db.completionLogDao().byId(logId)?.completedAt)
        offers.undo(offers.pending.value!!).join()
        assertNull(testDb.db.completionLogDao().byId(logId))
    }

    @Test
    fun `E4-B - desfazer a conclusao direto descarta a oferta do mesmo registro`() = runTest {
        val offers = offers(this)
        val task = repository.addTask("Pagar conta")
        offers.completeTask(task).join()
        val logId = (offers.pending.value!!.target as UndoTarget.CompletedTask).logId

        offers.discardTaskCompletion(logId + 1) // outro registro: fica
        assertEquals(UndoTarget.CompletedTask(logId), offers.pending.value?.target)

        offers.discardTaskCompletion(logId)
        assertNull("sem mensagem na tela", offers.pending.value)
    }
}
