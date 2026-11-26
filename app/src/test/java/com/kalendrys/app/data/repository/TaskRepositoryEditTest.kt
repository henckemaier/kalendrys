package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.RecurrenceUnit
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.linkList
import com.kalendrys.app.testutil.FakeReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 * Salvar na hora da tarefa (v1.9.0, ADR-0002 15.2): [TaskRepository.edit] aplica cada mudança sobre
 * a linha MAIS RECENTE e grava só as colunas dela; concluir e desfazer gravam só data e lembrete.
 * Cada caso de "alterada por fora" confere que nenhum dos dois lados se perde, e o alarme só é
 * refeito pela mudança de data, hora e repetição.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskRepositoryEditTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var repository: TaskRepository
    private lateinit var shopping: ShoppingRepository
    private var windowsClosed = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scheduler = FakeReminderScheduler(context)
        repository = TaskRepository(
            db.taskDao(),
            db.completionLogDao(),
            scheduler,
            db.shoppingItemDao(),
            beforeReminderChange = { windowsClosed++ },
            clock = SystemAppClock,
        )
        shopping = ShoppingRepository(db, clock = SystemAppClock)
    }

    @After
    fun tearDown() = db.close()

    private val tomorrow get() = LocalDate.now().plusDays(1)
    private fun futureTiming(days: Long = 1) = TaskTiming(date = LocalDate.now().plusDays(days), time = LocalTime.of(9, 0))

    private suspend fun task(id: Long) = db.taskDao().byId(id)!!

    @Test
    fun `titulo grava so o titulo e nao toca em alarme nem no catch-up`() = runTest {
        val saved = repository.addTask("Pagar contas", notes = "boleto", timing = futureTiming())
        scheduler.reset()
        windowsClosed = 0

        assertTrue(repository.edit(saved.id, TaskChange.Title("  Pagar luz ")))

        assertEquals(saved.copy(title = "Pagar luz"), task(saved.id))
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(0, windowsClosed)
    }

    @Test
    fun `titulo vazio nunca e gravado`() = runTest {
        val saved = repository.addTask("Pagar contas")

        assertFalse(repository.edit(saved.id, TaskChange.Title("   ")))

        assertEquals("Pagar contas", task(saved.id).title)
    }

    @Test
    fun `observacoes, local e lista nao tocam em alarme`() = runTest {
        val saved = repository.addTask("Tarefa", timing = futureTiming())
        scheduler.reset()
        windowsClosed = 0

        repository.edit(saved.id, TaskChange.Notes("n"))
        repository.edit(saved.id, TaskChange.Location("l"))
        repository.edit(saved.id, TaskChange.NewShoppingList("Farmácia"))

        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(0, windowsClosed)
    }

    @Test
    fun `data e hora juntas fecham a janela, cancelam e reagendam uma vez`() = runTest {
        val saved = repository.addTask("Tarefa", timing = futureTiming(1))
        scheduler.reset()
        windowsClosed = 0

        val timing = futureTiming(3).copy(repeat = ReminderRepeat.WEEKLY)
        assertTrue(repository.edit(saved.id, TaskChange.Timing(timing)))

        val after = task(saved.id)
        assertEquals(timing.date!!.toEpochDay(), after.dueDate)
        assertEquals(ReminderRepeat.WEEKLY, after.repeat)
        assertEquals(timing.reminderAt(java.time.ZoneId.systemDefault()), after.reminderAt)
        assertEquals(1, windowsClosed)
        assertEquals(listOf(saved.id), scheduler.cancelled)
        assertEquals(listOf(saved.id), scheduler.scheduled.map { it.task.id })
        // O alarme usa a tarefa como ficou (título inclusive).
        assertEquals(after, scheduler.scheduled.single().task)
    }

    @Test
    fun `cancelar no meio da nova data ainda grava e reagenda pela data nova`() = runTest {
        // NB-E1-B: o chamador é cancelado DEPOIS de gravar a data e antes do reagendamento (preso no
        // cancel do alarme). Sem NonCancellable, a leitura do banco do scheduleNext jogaria o
        // cancelamento e o alarme da data nova não seria marcado.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = object : FakeReminderScheduler(ApplicationProvider.getApplicationContext()) {
            override fun cancel(taskId: Long) {
                super.cancel(taskId)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val repo = TaskRepository(db.taskDao(), db.completionLogDao(), gated, db.shoppingItemDao(), clock = SystemAppClock)
        val saved = repo.addTask("Tarefa", timing = futureTiming(1))
        gated.reset()
        val timing = futureTiming(3)

        withContext(Dispatchers.Default) {
            val edit = launch { repo.edit(saved.id, TaskChange.Timing(timing)) }
            assertTrue("não chegou ao reagendamento", entered.await(5, TimeUnit.SECONDS))
            edit.cancel() // sair da tela no meio
            release.countDown()
            edit.join()
        }

        val after = task(saved.id)
        assertEquals(timing.date!!.toEpochDay(), after.dueDate)
        assertEquals("alarme da data nova marcado", listOf(after), gated.scheduled.map { it.task })
        assertEquals(timing.reminderAt(java.time.ZoneId.systemDefault()), gated.scheduled.single().triggerAt)
    }

    @Test
    fun `repeticao personalizada sem dia nao grava nada`() = runTest {
        val saved = repository.addTask("Tarefa", timing = futureTiming())
        scheduler.reset()

        val broken = futureTiming().copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0, intervalUnit = null)
        assertFalse(repository.edit(saved.id, TaskChange.Timing(broken)))

        assertEquals(saved, task(saved.id))
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `repeticao personalizada com intervalo grava os parametros juntos`() = runTest {
        val saved = repository.addTask("Tarefa", timing = futureTiming())

        val timing = futureTiming().copy(repeat = ReminderRepeat.CUSTOM, intervalEvery = 3, intervalUnit = RecurrenceUnit.DAYS)
        assertTrue(repository.edit(saved.id, TaskChange.Timing(timing)))

        val after = task(saved.id)
        assertEquals(ReminderRepeat.CUSTOM, after.repeat)
        assertEquals(3, after.intervalEvery)
        assertEquals(RecurrenceUnit.DAYS, after.intervalUnit)
    }

    @Test
    fun `mudanca que nao muda nada nao grava`() = runTest {
        val saved = repository.addTask("Tarefa")
        assertFalse(repository.edit(saved.id, TaskChange.Title("Tarefa")))
    }

    @Test
    fun `tarefa que nao existe mais - nada acontece nem no alarme`() = runTest {
        val saved = repository.addTask("Tarefa")
        repository.deleteTask(saved)
        scheduler.reset()

        assertFalse(repository.edit(saved.id, TaskChange.Title("Outra")))
        assertFalse(repository.edit(saved.id, TaskChange.Timing(futureTiming())))
        assertNull(db.taskDao().byId(saved.id))
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `nova data sobre concluida adiantada por fora - vale a edicao e o Desfazer nao devolve data errada`() = runTest {
        // N3 do Esquadro.
        val saved = repository.addTask("Tarefa", timing = futureTiming(2))
        val logId = repository.logCompletion(saved.id)
        val edited = futureTiming(5)

        assertTrue(repository.edit(saved.id, TaskChange.Timing(edited)))
        // O registro da conclusão continua.
        assertEquals(saved.id, db.completionLogDao().byId(logId)!!.taskId)

        repository.undoCompletion(logId)

        // A guarda do Desfazer vê que a data não é mais a que a conclusão gravou: a edição vence.
        val after = task(saved.id)
        assertEquals(edited.date!!.toEpochDay(), after.dueDate)
        assertEquals(edited.reminderAt(java.time.ZoneId.systemDefault()), after.reminderAt)
        assertNull(db.completionLogDao().byId(logId))
    }

    // --- Alterada por fora: nenhum dos dois lados se perde -----------------------------------------

    @Test
    fun `concluida adiantada por fora - editar o titulo mantem a data trazida para hoje`() = runTest {
        val saved = repository.addTask("Tarefa", timing = futureTiming(2))
        repository.logCompletion(saved.id)
        val concluded = task(saved.id)
        assertEquals(LocalDate.now().toEpochDay(), concluded.dueDate)

        repository.edit(saved.id, TaskChange.Title("Tarefa nova"))

        assertEquals(concluded.copy(title = "Tarefa nova"), task(saved.id))
    }

    @Test
    fun `concluir adiantado e desfazer nao regravam o titulo editado no meio`() = runTest {
        val saved = repository.addTask("Antes", notes = "obs", timing = futureTiming(2))
        val logId = repository.logCompletion(saved.id)
        repository.edit(saved.id, TaskChange.Title("Depois"))
        repository.edit(saved.id, TaskChange.Notes("obs nova"))

        repository.undoCompletion(logId)

        val after = task(saved.id)
        // Data e lembrete voltam; o que foi editado entre concluir e desfazer fica.
        assertEquals(saved.dueDate, after.dueDate)
        assertEquals(saved.reminderAt, after.reminderAt)
        assertEquals("Depois", after.title)
        assertEquals("obs nova", after.notes)
    }

    @Test
    fun `lista excluida por fora - editar o titulo nao devolve o vinculo`() = runTest {
        val listId = shopping.createList("Mercado")
        val saved = repository.addTask("Tarefa").also { db.linkList(it.id, listId) }
        shopping.deleteList(listId)

        repository.edit(saved.id, TaskChange.Title("Tarefa nova"))

        val after = task(saved.id)
        assertEquals("Tarefa nova", after.title)
        assertNull(after.shoppingListId)
    }

    @Test
    fun `lista nova cria e vincula numa vez`() = runTest {
        val saved = repository.addTask("Tarefa", notes = "obs")

        assertTrue(repository.edit(saved.id, TaskChange.NewShoppingList("  Farmácia ")))

        val lists = db.shoppingListDao().all()
        assertEquals(listOf("Farmácia"), lists.map { it.name })
        assertEquals(saved.copy(shoppingListId = lists.single().id), task(saved.id))
    }

    @Test
    fun `lista nova duas vezes seguidas - uma lista so, sem lista sem dona`() = runTest {
        // A1 da revisão da T3: toque duplo em "Adicionar lista".
        val saved = repository.addTask("Feira")

        assertTrue(repository.edit(saved.id, TaskChange.NewShoppingList("Feira")))
        assertFalse(repository.edit(saved.id, TaskChange.NewShoppingList("Feira")))

        val lists = db.shoppingListDao().all()
        assertEquals(1, lists.size)
        assertEquals(lists.single().id, task(saved.id).shoppingListId)
    }

    @Test
    fun `lista nova com vinculo orfao cria a lista`() = runTest {
        val saved = repository.addTask("Feira").also { db.linkList(it.id, 404) }

        assertTrue(repository.edit(saved.id, TaskChange.NewShoppingList("Feira")))

        assertEquals(db.shoppingListDao().all().single().id, task(saved.id).shoppingListId)
    }

    @Test
    fun `lista nova com nome em branco nao cria lista`() = runTest {
        val saved = repository.addTask("Tarefa")

        assertFalse(repository.edit(saved.id, TaskChange.NewShoppingList(" ")))

        assertTrue(db.shoppingListDao().all().isEmpty())
        assertNull(task(saved.id).shoppingListId)
    }

    @Test
    fun `mudar a data de uma tarefa concluida e encerrada nao marca alarme`() = runTest {
        val saved = repository.addTask("Tarefa", timing = TaskTiming(date = LocalDate.now(), time = LocalTime.of(23, 59)))
        repository.logCompletion(saved.id)
        scheduler.reset()

        // Para um dia que já passou: continua encerrada (concluída no dia dela ou depois).
        repository.edit(saved.id, TaskChange.Timing(TaskTiming(date = LocalDate.now().minusDays(1), time = LocalTime.of(9, 0))))

        assertEquals(listOf(saved.id), scheduler.cancelled)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `mudar a data para depois reabre e agenda`() = runTest {
        val saved = repository.addTask("Tarefa", timing = TaskTiming(date = LocalDate.now()))
        repository.logCompletion(saved.id)
        scheduler.reset()

        repository.edit(saved.id, TaskChange.Timing(TaskTiming(date = tomorrow.plusDays(1), time = LocalTime.of(9, 0))))

        assertEquals(listOf(saved.id), scheduler.scheduled.map { it.task.id })
    }
}
