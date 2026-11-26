package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.dao.TaskWithList
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.testutil.linkList
import com.kalendrys.app.testutil.FakeReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun todayTiming(repeat: ReminderRepeat = ReminderRepeat.NONE) =
        TaskTiming(date = LocalDate.now(), time = LocalTime.of(9, 0), repeat = repeat)

    /** Lembrete sempre no futuro: desde a E1 só se marca alarme que vai tocar (hoje às 9h já pode ter passado). */
    private fun futureTiming() = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0))

    @Test
    fun `adicionar tarefa agenda o alarme`() = runTest {
        val task = repository.addTask("Comprar pão", timing = futureTiming())

        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `editar data e hora da tarefa cancela o alarme antigo e reagenda`() = runTest {
        val task = repository.addTask("Tarefa", timing = futureTiming())
        scheduler.reset()

        repository.edit(task.id, TaskChange.Timing(futureTiming().copy(time = LocalTime.of(10, 0))))

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
        val task = repository.addTask("Tarefa", timing = futureTiming())
        val logId = repository.logCompletion(task.id)
        scheduler.reset()

        repository.undoCompletion(logId)

        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
    }

    @Test
    fun `falha no gancho beforeReminderChange nao impede gravar a tarefa`() = runTest {
        // A notificação do catch-up falhou: perder a gravação seria pior (parecer do Esquadro, NB-2).
        val failing = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock) {
            throw IllegalStateException("notificação falhou")
        }
        val task = failing.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        failing.edit(task.id, TaskChange.Timing(todayTiming(ReminderRepeat.DAILY).copy(time = LocalTime.of(10, 0))))
        failing.edit(task.id, TaskChange.Title("Treino forte"))
        val logId = failing.logCompletion(task.id)
        failing.undoCompletion(logId)
        failing.deleteTask(task.copy(title = "Treino forte"))
        failing.restoreTask(TaskWithList(task.copy(title = "Treino forte"), null, emptyList()))
        assertEquals(listOf("Treino forte"), db.taskDao().all().map { it.title })
        assertEquals(0, db.completionLogDao().count())
    }

    @Test
    fun `gancho beforeReminderChange roda antes de gravar em add update e restore`() = runTest {
        // O gancho vê o banco ANTES da escrita: é o que fecha a janela do catch-up (ADR-0003).
        val seen = mutableListOf<List<String>>()
        val hooked = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock) {
            seen += db.taskDao().all().map { it.title }
        }
        val task = hooked.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        // Só a mudança de data/hora fecha a janela (o título não toca em alarme, 15.2).
        hooked.edit(task.id, TaskChange.Timing(todayTiming(ReminderRepeat.DAILY).copy(time = LocalTime.of(10, 0))))
        hooked.edit(task.id, TaskChange.Title("Treino forte"))
        hooked.deleteTask(task.copy(title = "Treino forte"))
        hooked.restoreTask(TaskWithList(task.copy(title = "Treino forte"), null, emptyList()))
        assertEquals(listOf(emptyList(), listOf("Treino"), emptyList<String>()), seen)
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
        val shopping = ShoppingRepository(db, clock = SystemAppClock)
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
        db.linkList(task.id, listId)

        val logId = repository.logCompletion(task.id)
        assertEquals(listOf(false, false), picked(listId))

        repository.undoCompletion(logId)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `concluir tarefa unica nao mexe na lista`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Compra do mês", timing = todayTiming())
        db.linkList(task.id, listId)

        repository.logCompletion(task.id)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `excluir tarefa apaga a lista e os itens, e restaurar devolve os tres com os mesmos ids`() = runTest {
        // v1.10.0 (ADR-0002 16.10.4): a lista é da tarefa.
        val listId = listWithPickedItems()
        val task = repository.addTask("Feira", timing = todayTiming())
        db.linkList(task.id, listId)
        val list = db.shoppingListDao().byId(listId)!!
        val items = db.shoppingItemDao().all().filter { it.listId == listId }.sortedBy { it.id }

        val deleted = checkNotNull(repository.deleteTask(task))
        assertNull(db.taskDao().byId(task.id))
        assertNull("a lista sai com a tarefa", db.shoppingListDao().byId(listId))
        assertTrue("os itens também", db.shoppingItemDao().all().none { it.listId == listId })
        assertEquals(list, deleted.list)
        assertEquals(items, deleted.items)

        repository.restoreTask(deleted)
        assertEquals(listId, db.taskDao().byId(task.id)!!.shoppingListId)
        assertEquals(list, db.shoppingListDao().byId(listId))
        assertEquals(items, db.shoppingItemDao().all().filter { it.listId == listId }.sortedBy { it.id })
    }

    @Test
    fun `dona da lista vem do banco - e sem dona ou sem lista nao ha`() = runTest {
        // O link de lista abre sobre o detalhe da dona (v1.10.0, ADR-0002 16.10.4).
        val listId = listWithPickedItems()
        val task = repository.addTask("Feira", timing = todayTiming())
        db.linkList(task.id, listId)

        assertEquals(task.id, repository.ownerOfList(listId)?.id)
        assertNull("lista que não existe", repository.ownerOfList(404))
        db.linkList(task.id, null)
        assertNull("lista sem dona", repository.ownerOfList(listId))
    }

    @Test
    fun `excluir a tarefa que nao existe mais devolve null`() = runTest {
        val task = repository.addTask("Feira", timing = todayTiming())
        checkNotNull(repository.deleteTask(task))

        assertNull(repository.deleteTask(task))
    }

    @Test
    fun `vinculo orfao conclui e desfaz sem falhar`() = runTest {
        val task = repository.addTask("Órfã", timing = todayTiming(ReminderRepeat.DAILY))
        // Vínculo órfão (lista inexistente): só pelo DAO, o edit confere a lista e recusaria.
        db.taskDao().updateShoppingList(task.id, 999)

        val logId = repository.logCompletion(task.id)
        repository.undoCompletion(logId)
        assertTrue(db.completionLogDao().all().isEmpty())
    }

    @Test
    fun `tarefas vinculadas trazem o estado pendente`() = runTest {
        // v1.10.0: lista de UMA tarefa; cada lista traz a sua, com o estado dela.
        val pendingList = listWithPickedItems()
        val doneList = listWithPickedItems()
        val pending = repository.addTask("Pendente", timing = todayTiming())
        val done = repository.addTask("Feita", timing = todayTiming())
        db.linkList(pending.id, pendingList)
        db.linkList(done.id, doneList)
        repository.logCompletion(done.id)

        assertEquals(
            listOf(com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask(pending.id, pending = true)),
            repository.linkedTasks(pendingList),
        )
        assertEquals(
            listOf(com.kalendrys.app.domain.ShoppingCompletionOffer.LinkedTask(done.id, pending = false)),
            repository.linkedTasks(doneList),
        )
    }

    // --- Lembrete exibido some ao concluir ou excluir (v1.6.0, decisão do usuário) ---
    // Todos os caminhos de conclusão (detalhe, lista de tarefas, timeline, oferta da lista, widget e a
    // ação Concluir da notificação) passam por logCompletion; toda exclusão passa por deleteTask.

    @Test
    fun `concluir tarefa unica dispensa a notificacao e cancela o alarme`() = runTest {
        val task = repository.addTask("Única", timing = todayTiming())
        scheduler.reset()

        repository.logCompletion(task.id)

        assertEquals(listOf(task.id), scheduler.dismissed)
        assertEquals(listOf(task.id), scheduler.cancelled)
    }

    @Test
    fun `concluir tarefa repetida dispensa a notificacao e mantem o proximo agendamento`() = runTest {
        val task = repository.addTask("Diária", timing = todayTiming(ReminderRepeat.DAILY))
        scheduler.reset()

        repository.logCompletion(task.id)

        assertEquals(listOf(task.id), scheduler.dismissed)
        assertTrue("o alarme da próxima ocorrência não é cancelado", scheduler.cancelled.isEmpty())
    }

    @Test
    fun `excluir tarefa dispensa a notificacao`() = runTest {
        val task = repository.addTask("Excluir", timing = todayTiming())
        scheduler.reset()

        repository.deleteTask(task)

        assertEquals(listOf(task.id), scheduler.dismissed)
        assertEquals(listOf(task.id), scheduler.cancelled)
    }

    @Test
    fun `desfazer conclusao ou exclusao reagenda sem reexibir`() = runTest {
        val task = repository.addTask("Desfazer", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)))
        val logId = repository.logCompletion(task.id)
        scheduler.reset()

        repository.undoCompletion(logId)
        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        assertTrue("desfazer não mexe em notificação", scheduler.dismissed.isEmpty())

        val current = db.taskDao().byId(task.id)!!
        repository.deleteTask(current)
        scheduler.reset()
        repository.restoreTask(TaskWithList(current, null, emptyList()))
        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        assertTrue(scheduler.dismissed.isEmpty())
    }

    @Test
    fun `dispensar sem notificacao exibida nao falha`() {
        // Scheduler REAL: cancelar uma notificação que não existe é no-op.
        ReminderScheduler(ApplicationProvider.getApplicationContext()).dismissNotification(9_999)
    }

    @Test
    fun `tarefa unica de amanha conta como pendente e repetida concluida hoje nao`() = runTest {
        val tomorrowList = listWithPickedItems()
        val dailyList = listWithPickedItems()
        val tomorrow = repository.addTask("Amanhã", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)))
        val daily = repository.addTask("Diária", timing = todayTiming(ReminderRepeat.DAILY))
        db.linkList(tomorrow.id, tomorrowList)
        db.linkList(daily.id, dailyList)
        repository.logCompletion(daily.id)

        assertEquals(mapOf(tomorrow.id to true), repository.linkedTasks(tomorrowList).associate { it.taskId to it.pending })
        assertEquals(mapOf(daily.id to false), repository.linkedTasks(dailyList).associate { it.taskId to it.pending })
    }

    @Test
    fun `tarefa unica de amanha ja concluida nao conta como pendente`() = runTest {
        val listId = listWithPickedItems()
        val tomorrow = repository.addTask("Amanhã", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)))
        db.linkList(tomorrow.id, listId)
        repository.logCompletion(tomorrow.id)

        assertEquals(listOf(false), repository.linkedTasks(listId).map { it.pending })
    }

    // --- Desfazer conclusão adiantada devolve data e lembrete (v1.6.0; definitivo no banco 11, v1.8.0) ---

    private fun tomorrowAtNine() = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0))

    @Test
    fun `desfazer conclusao adiantada devolve data e lembrete e reagenda`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val original = db.taskDao().byId(task.id)!!
        val logId = repository.logCompletion(task.id)
        assertEquals(LocalDate.now().toEpochDay(), db.taskDao().byId(task.id)!!.dueDate)
        scheduler.reset()

        repository.undoCompletion(logId)

        val restored = db.taskDao().byId(task.id)!!
        assertEquals(original.dueDate, restored.dueDate)
        assertEquals(original.reminderAt, restored.reminderAt)
        assertEquals(listOf(restored), scheduler.scheduled.map { it.task })
    }

    @Test
    fun `usuario editou a data entre concluir e desfazer e a edicao vence`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val logId = repository.logCompletion(task.id)
        val edited = LocalDate.now().plusDays(5).toEpochDay()
        repository.edit(task.id, TaskChange.Timing(TaskTiming(date = LocalDate.ofEpochDay(edited))))

        repository.undoCompletion(logId)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(edited, after.dueDate)
        assertNull(after.reminderAt)
    }

    @Test
    fun `usuario pos lembrete mantendo a data entre concluir e desfazer e a edicao vence`() = runTest {
        // Condição "sem lembrete" do desfazer adiantado: a data continua a da conclusão, mas o usuário
        // pôs um lembrete novo. A edição dele vence e a data/lembrete originais não voltam.
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val logId = repository.logCompletion(task.id)
        val completed = db.taskDao().byId(task.id)!!
        val newReminder = LocalDate.now().atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.edit(task.id, TaskChange.Timing(TaskTiming(date = LocalDate.ofEpochDay(completed.dueDate!!), time = LocalTime.of(23, 59))))

        repository.undoCompletion(logId)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(LocalDate.now().toEpochDay(), after.dueDate)
        assertEquals(newReminder, after.reminderAt)
    }

    @Test
    fun `tarefa unica adiantada com lista nao zera e desfazer devolve a data sem mexer na lista`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Mercado amanhã", timing = tomorrowAtNine()).also { db.linkList(it.id, listId) }
        val original = db.taskDao().byId(task.id)!!

        val logId = repository.logCompletion(task.id)
        assertEquals(listOf(true, true), picked(listId))

        repository.undoCompletion(logId)
        val restored = db.taskDao().byId(task.id)!!
        assertEquals(original.dueDate, restored.dueDate)
        assertEquals(original.reminderAt, restored.reminderAt)
        assertEquals(listId, restored.shoppingListId)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `processo novo ainda desfaz a conclusao adiantada com data e lembrete de volta`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val original = db.taskDao().byId(task.id)!!
        val logId = repository.logCompletion(task.id)
        val log = db.completionLogDao().byId(logId)!!
        assertEquals(original.dueDate, log.previousDueDate)
        assertEquals(original.reminderAt, log.previousReminderAt)
        assertEquals(LocalDate.now().toEpochDay(), log.rewrittenDueDate)
        // Simula o processo morto: um repositório novo, sem nada em memória, só o banco.
        scheduler.reset()
        val fresh = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock)

        fresh.undoCompletion(logId)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(original.dueDate, after.dueDate)
        assertEquals(original.reminderAt, after.reminderAt)
        assertEquals(listOf(after), scheduler.scheduled.map { it.task })
        assertTrue(db.completionLogDao().all().isEmpty())
    }

    @Test
    fun `tarefa repetida e tarefa de hoje nao guardam data anterior no registro`() = runTest {
        val daily = repository.addTask("Diária", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0), repeat = ReminderRepeat.DAILY))
        val today = repository.addTask("Hoje", timing = todayTiming())
        val logs = listOf(repository.logCompletion(daily.id), repository.logCompletion(today.id))
            .map { db.completionLogDao().byId(it)!! }
        assertTrue(logs.all { it.previousDueDate == null && it.previousReminderAt == null && it.rewrittenDueDate == null })
    }


    @Test
    fun `falha depois de apagar o registro nao deixa a tarefa com a data reescrita`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val logId = repository.logCompletion(task.id)
        val rewritten = db.taskDao().byId(task.id)!!
        // Falha simulada na gravação da tarefa, que vem DEPOIS de apagar o registro na transação.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER falha_no_desfazer BEFORE UPDATE ON tasks BEGIN SELECT RAISE(ABORT, 'falha simulada'); END",
        )

        val error = runCatching { repository.undoCompletion(logId) }.exceptionOrNull()

        assertTrue("esperava a falha simulada, veio $error", error != null)
        // A transação inteira voltou: o registro continua lá para restaurar depois.
        assertEquals(logId, db.completionLogDao().byId(logId)?.id)
        assertEquals(rewritten, db.taskDao().byId(task.id))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER falha_no_desfazer")
        repository.undoCompletion(logId)
        assertEquals(LocalDate.now().plusDays(1).toEpochDay(), db.taskDao().byId(task.id)!!.dueDate)
        assertTrue(db.completionLogDao().all().isEmpty())
    }


    // --- E1 (v1.8.0, ADR-0002 14.3): o alarme marcado é a próxima ocorrência que será ENTREGUE ---

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `diaria concluida antes do horario nao tem alarme nesse dia e desfazer devolve`() = runTest {
        // Âncora depois de amanhã: o relógio real do teste nunca cruza essas datas.
        val day = LocalDate.now().plusDays(2)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        assertEquals(at(day, 10), scheduler.scheduled.last().triggerAt)
        scheduler.reset()

        // Concluída no próprio dia, antes das 10h: o alarme desse dia sai, vale o do dia seguinte.
        val logId = repository.logCompletion(task.id, completedAt = at(day, 7))
        assertEquals(listOf(at(day.plusDays(1), 10)), scheduler.scheduled.map { it.triggerAt })
        scheduler.reset()

        repository.undoCompletion(logId)
        assertEquals(listOf(at(day, 10)), scheduler.scheduled.map { it.triggerAt })
    }

    @Test
    fun `catch-up e restauro marcam a mesma ocorrencia que o diagnostico mostra`() = runTest {
        val day = LocalDate.now().plusDays(2)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        db.completionLogDao().insert(com.kalendrys.app.data.local.entity.CompletionLog(taskId = task.id, completedAt = at(day, 7)))
        scheduler.reset()
        val now = System.currentTimeMillis()

        repository.rescheduleReminders(now)

        val marked = scheduler.rescheduledAlarms.single { it.task.id == task.id }.triggerAt
        assertEquals(at(day.plusDays(1), 10), marked)
        assertEquals(marked, repository.nextReminderToDeliver(now))
    }


    @Test
    fun `tarefa com alarme marcado e conta sem ocorrencia termina sem alarme`() = runTest {
        // Diária com alarme marcado; todas as ocorrências que a conta olha (o limite de dias pulados)
        // já concluídas: não há ocorrência a entregar, então o alarme é cancelado (NB-1 da revisão da E1).
        val day = LocalDate.now().plusDays(2)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        assertEquals(listOf(task.id), scheduler.scheduled.map { it.task.id })
        (0L..40L).forEach { n ->
            db.completionLogDao().insert(com.kalendrys.app.data.local.entity.CompletionLog(taskId = task.id, completedAt = at(day.plusDays(n), 7)))
        }
        scheduler.reset()

        repository.logCompletion(task.id, completedAt = at(day, 8))

        assertTrue("sem ocorrência não marca alarme", scheduler.scheduled.isEmpty())
        assertTrue("o alarme marcado é cancelado", task.id in scheduler.cancelled)
    }


    // --- Conclusão retroativa (dia anterior a hoje, pela Hoje): sem efeito de hoje ---

    private fun yesterdayAt(hour: Int) =
        LocalDate.now().minusDays(1).atTime(hour, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `repetida concluida ontem nao mexe no alarme, na notificacao nem na lista`() = runTest {
        val listId = listWithPickedItems()
        val daily = repository.addTask(
            "Remédio",
            timing = TaskTiming(date = LocalDate.now().minusDays(1), time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY),
        ).also { db.linkList(it.id, listId) }
        val now = at(LocalDate.now(), 11)
        val alarmBefore = repository.nextReminderToDeliver(now)
        scheduler.reset()

        // Depois das 10h de hoje: a notificação na tela é a de HOJE, e concluir ontem não a tira (NB-E4-A).
        val logId = repository.logCompletion(daily.id, completedAt = yesterdayAt(11), now = now)

        assertTrue("alarme não mexido", scheduler.scheduled.isEmpty() && scheduler.cancelled.isEmpty())
        assertTrue("notificação da tela não tirada", scheduler.dismissed.isEmpty())
        assertEquals("lista não zerada", listOf(true, true), picked(listId))
        assertEquals(LocalDate.now().minusDays(1), java.time.Instant.ofEpochMilli(db.completionLogDao().byId(logId)!!.completedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        // A conta do alarme só olha conclusões de hoje em diante: a ocorrência de hoje continua a próxima.
        assertEquals(alarmBefore, repository.nextReminderToDeliver(now))
    }

    // --- NB-E4-A: a notificação na tela é a da ÚLTIMA ocorrência que já tocou ---

    private suspend fun dailyAtTen() = repository.addTask(
        "Remédio",
        timing = TaskTiming(date = LocalDate.now().minusDays(5), time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY),
    )

    @Test
    fun `antes do horario de hoje, concluir ontem tira a notificacao de ontem e o lembrete de hoje continua`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        val now = at(today, 9)
        scheduler.reset()

        repository.logCompletion(task.id, completedAt = at(today.minusDays(1), 11), now = now)

        assertEquals("a visível era a de ontem: sai", listOf(task.id), scheduler.dismissed)
        // Caminho completo: o alarme de hoje continua marcado e a ocorrência de hoje continua pendente.
        assertTrue("alarme não mexido", scheduler.scheduled.isEmpty() && scheduler.cancelled.isEmpty())
        assertEquals(at(today, 10), repository.nextReminderToDeliver(now))
        assertTrue(db.completionLogDao().all().none { java.time.Instant.ofEpochMilli(it.completedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today })
    }

    @Test
    fun `depois do horario de hoje, concluir ontem nao tira a notificacao de hoje`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        scheduler.reset()

        repository.logCompletion(task.id, completedAt = at(today.minusDays(1), 11), now = at(today, 11))

        assertTrue(scheduler.dismissed.isEmpty())
    }

    @Test
    fun `antes do horario de hoje, concluir anteontem nao tira a notificacao de ontem`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        scheduler.reset()

        repository.logCompletion(task.id, completedAt = at(today.minusDays(2), 11), now = at(today, 9))

        assertTrue(scheduler.dismissed.isEmpty())
        assertEquals(at(today, 10), repository.nextReminderToDeliver(at(today, 9)))
    }

    @Test
    fun `retroativa sem ocorrencia na janela nao tira a notificacao`() = runTest {
        // Repetida que começa hoje às 10h, concluída por ontem antes do horário: nada tocou desde ontem.
        val today = LocalDate.now()
        val task = repository.addTask(
            "Nova",
            timing = TaskTiming(date = today, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY),
        )
        scheduler.reset()

        repository.logCompletion(task.id, completedAt = at(today.minusDays(1), 11), now = at(today, 9))

        assertTrue(scheduler.dismissed.isEmpty())
    }

    @Test
    fun `unica atrasada concluida no dia dela fica encerrada, sem conclusao adiantada`() = runTest {
        val yesterday = LocalDate.now().minusDays(1)
        val task = repository.addTask("Pagar conta", timing = TaskTiming(date = yesterday, time = LocalTime.of(9, 0)))
        scheduler.reset()

        val logId = repository.logCompletion(task.id, completedAt = yesterdayAt(11))
        // A notificação do lembrete atrasado (na tela) sai: a única encerra e não tem ocorrência de hoje.
        assertEquals(listOf(task.id), scheduler.dismissed)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(yesterday.toEpochDay(), after.dueDate)
        assertNull(db.completionLogDao().byId(logId)!!.previousDueDate)
        assertTrue(com.kalendrys.app.domain.TaskSchedule.isFinished(after, db.completionLogDao().lastCompletion(task.id), java.time.ZoneId.systemDefault()))
        // Desfazer pelo id do registro.
        repository.undoCompletion(logId)
        assertNull(db.completionLogDao().byId(logId))
        assertEquals(yesterday.toEpochDay(), db.taskDao().byId(task.id)!!.dueDate)
    }

    // --- NB-E5-A: "Concluir" da notificação grava no dia da ocorrência que ela mostra ---

    private fun dayOf(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /** Diária das 10h; as conclusões gravadas, pelo dia. */
    private suspend fun logDays(taskId: Long) = db.completionLogDao().all().filter { it.taskId == taskId }.map { dayOf(it.completedAt) }

    private suspend fun assertTodayPendingAndAlarmIntact(taskId: Long, now: Long, todayAlarm: Long) {
        val today = dayOf(now)
        assertTrue("hoje continua pendente", today !in logDays(taskId))
        assertTrue("alarme de hoje intacto", scheduler.scheduled.isEmpty() && scheduler.cancelled.isEmpty())
        assertEquals(todayAlarm, repository.nextReminderToDeliver(now))
    }

    @Test
    fun `notificacao de ontem entregue atrasada, Concluir grava ontem e hoje fica pendente`() = runTest {
        // Ocorrência de ontem entregue tarde (catch-up logo depois da meia-noite): a notificação é de ontem.
        val task = dailyAtTen()
        val today = LocalDate.now()
        val now = at(today, 0) + 40 * 60_000L
        scheduler.reset()

        assertTrue(repository.completeFromReminder(task.id, today.minusDays(1), now = now))

        assertEquals(listOf(today.minusDays(1)), logDays(task.id))
        assertTodayPendingAndAlarmIntact(task.id, now, at(today, 10))
    }

    @Test
    fun `notificacao de ontem que ficou na tela, Concluir hoje antes do horario grava ontem`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        val now = at(today, 9)
        scheduler.reset()

        assertTrue(repository.completeFromReminder(task.id, today.minusDays(1), now = now))

        assertEquals(listOf(today.minusDays(1)), logDays(task.id))
        assertTodayPendingAndAlarmIntact(task.id, now, at(today, 10))
    }

    @Test
    fun `horario editado depois da notificacao de ontem, Concluir grava ontem pelo dia da notificacao`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        // A pessoa mudou o horário para as 11h depois que a de ontem tocou: o dia vem da notificação.
        repository.edit(
            task.id,
            TaskChange.Timing(TaskTiming(date = today.minusDays(5), time = LocalTime.of(11, 0), repeat = ReminderRepeat.DAILY)),
        )
        val now = at(today, 9)
        scheduler.reset()

        assertTrue(repository.completeFromReminder(task.id, today.minusDays(1), now = now))

        assertEquals(listOf(today.minusDays(1)), logDays(task.id))
        assertTodayPendingAndAlarmIntact(task.id, now, at(today, 11))
    }

    @Test
    fun `Concluir duas vezes, ou pela Hoje e depois pela notificacao, nao duplica`() = runTest {
        val task = dailyAtTen()
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val now = at(today, 9)

        // Pela Hoje (conclusão retroativa) e depois pela notificação de ontem.
        repository.logCompletion(task.id, completedAt = at(yesterday, 18), now = now)
        assertEquals(false, repository.completeFromReminder(task.id, yesterday, now = now))
        // E pela notificação duas vezes, num outro dia.
        assertTrue(repository.completeFromReminder(task.id, today.minusDays(2), now = now))
        assertEquals(false, repository.completeFromReminder(task.id, today.minusDays(2), now = now))

        assertEquals(listOf(today.minusDays(2), yesterday), logDays(task.id).sorted())
    }

    @Test
    fun `tarefa unica atrasada concluida pela notificacao conclui agora`() = runTest {
        val today = LocalDate.now()
        val task = repository.addTask("Pagar conta", timing = TaskTiming(date = today.minusDays(1), time = LocalTime.of(10, 0)))
        val now = at(today, 9)

        assertTrue(repository.completeFromReminder(task.id, today.minusDays(1), now = now))

        assertEquals(listOf(now), db.completionLogDao().all().filter { it.taskId == task.id }.map { it.completedAt })
    }

    @Test
    fun `notificacao sem o dia conclui agora, como sempre`() = runTest {
        val task = dailyAtTen()
        val now = at(LocalDate.now(), 11)

        assertTrue(repository.completeFromReminder(task.id, occurrenceDay = null, now = now))

        assertEquals(listOf(now), db.completionLogDao().all().filter { it.taskId == task.id }.map { it.completedAt })
    }

    // --- NB-3: concluir de novo uma tarefa única encerrada não cria outro registro ---

    @Test
    fun `toque duplo em tarefa unica gera um registro so`() = runTest {
        val task = repository.addTask("Ligar", timing = todayTiming())
        val first = repository.logCompletion(task.id)
        val second = repository.logCompletion(task.id)

        assertEquals(first, second)
        assertEquals(1, db.completionLogDao().all().size)
    }

    @Test
    fun `duas conclusoes simultaneas da mesma tarefa unica geram um registro so`() = runTest {
        val task = repository.addTask("Ligar", timing = tomorrowAtNine())
        val ids = (1..2).map { async(Dispatchers.Default) { repository.logCompletion(task.id) } }.awaitAll()

        assertEquals(ids[0], ids[1])
        assertEquals(1, db.completionLogDao().all().size)
        // A conclusão adiantada continua desfazível com data e lembrete de volta.
        repository.undoCompletion(ids[0])
        assertEquals(LocalDate.now().plusDays(1).toEpochDay(), db.taskDao().byId(task.id)!!.dueDate)
    }

    @Test
    fun `tarefa repetida concluida duas vezes continua gerando dois registros`() = runTest {
        val daily = repository.addTask("Diária", timing = TaskTiming(date = LocalDate.now(), time = LocalTime.of(9, 0), repeat = ReminderRepeat.DAILY))
        repository.logCompletion(daily.id)
        repository.logCompletion(daily.id)
        assertEquals(2, db.completionLogDao().all().size)
    }

    @Test
    fun `tarefa unica reaberta pela data pode ser concluida de novo`() = runTest {
        val task = repository.addTask("Ligar", timing = todayTiming())
        repository.logCompletion(task.id)
        // Mudar a data para frente reabre a tarefa: a próxima conclusão é um registro novo.
        repository.edit(task.id, TaskChange.Timing(TaskTiming(date = LocalDate.now().plusDays(3))))
        repository.logCompletion(task.id, completedAt = LocalDate.now().plusDays(3).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertEquals(2, db.completionLogDao().all().size)
    }

    // --- Lince (revisão da E0): toque duplo + Desfazer, corrida concluir × desfazer, tarefa excluída ---

    @Test
    fun `toque duplo na conclusao adiantada e Desfazer pelo id do segundo toque devolve data e lembrete`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val original = db.taskDao().byId(task.id)!!
        val first = repository.logCompletion(task.id)
        // O segundo toque (ou o widget/notificação) devolve o MESMO registro: a snackbar dele desfaz a conclusão.
        val second = repository.logCompletion(task.id)
        assertEquals(first, second)

        repository.undoCompletion(second)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(original.dueDate, after.dueDate)
        assertEquals(original.reminderAt, after.reminderAt)
        assertTrue(db.completionLogDao().all().isEmpty())
        // O Desfazer do primeiro toque, que chega depois, não acha mais o registro e não mexe em nada.
        repository.undoCompletion(first)
        assertEquals(after, db.taskDao().byId(task.id))
    }

    @Test
    fun `concluir e desfazer ao mesmo tempo nunca deixam a data reescrita sem registro`() = runTest {
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val today = LocalDate.now().toEpochDay()
        repeat(15) { round ->
            val task = repository.addTask("Corrida $round", timing = tomorrowAtNine())
            val original = db.taskDao().byId(task.id)!!
            val logId = repository.logCompletion(task.id)

            listOf(
                async(Dispatchers.Default) { repository.undoCompletion(logId) },
                async(Dispatchers.Default) { repository.logCompletion(task.id) },
            ).awaitAll()

            val after = db.taskDao().byId(task.id)!!
            val logs = db.completionLogDao().all().filter { it.taskId == task.id }
            when (logs.size) {
                // Concluir chegou antes (devolveu o mesmo registro) e o desfazer o apagou: tarefa como antes.
                0 -> {
                    assertEquals("rodada $round", tomorrow, after.dueDate)
                    assertEquals("rodada $round", original.reminderAt, after.reminderAt)
                }
                // Desfazer chegou antes e concluir adiantou de novo: um registro com o que ele sobrescreveu.
                1 -> {
                    assertEquals("rodada $round", today, after.dueDate)
                    assertNull("rodada $round", after.reminderAt)
                    assertEquals("rodada $round", tomorrow, logs.single().previousDueDate)
                    assertEquals("rodada $round", original.reminderAt, logs.single().previousReminderAt)
                }
                else -> throw AssertionError("rodada $round: ${logs.size} registros")
            }
        }
    }

    @Test
    fun `desfazer a conclusao de uma tarefa ja excluida so apaga o registro e nao agenda nada`() = runTest {
        val task = repository.addTask("Amanhã", timing = tomorrowAtNine())
        val logId = repository.logCompletion(task.id)
        repository.deleteTask(db.taskDao().byId(task.id)!!)
        scheduler.reset()

        repository.undoCompletion(logId)

        assertNull(db.taskDao().byId(task.id))
        assertNull(db.completionLogDao().byId(logId))
        assertTrue(scheduler.scheduled.isEmpty())
    }

    // --- E1, corrida: o reagendamento geral (catch-up) lê as conclusões, o desfazer entra no meio ---

    @Test
    fun `desfazer no meio do reagendamento geral nao perde o alarme do dia desfeito`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marked = mutableMapOf<Long, Long>()
        var undoInTheMiddle: (() -> Unit)? = null
        var undoJob: kotlinx.coroutines.Job? = null
        // Marca no "sistema" o último alarme de cada tarefa. O primeiro rescheduleAll já recebeu o que o
        // catch-up calculou (com a conclusão ainda no banco) e, antes de marcar, o desfazer acontece.
        val racing = object : ReminderScheduler(context) {
            override fun schedule(task: com.kalendrys.app.data.local.entity.Task, triggerAt: Long) {
                marked[task.id] = triggerAt
            }
            override fun cancel(taskId: Long) {
                marked.remove(taskId)
            }
            override fun dismissNotification(taskId: Long) = Unit
            override fun rescheduleAll(alarms: List<com.kalendrys.app.reminder.ReminderAlarm>) {
                undoInTheMiddle?.also { undoInTheMiddle = null }?.invoke()
                super.rescheduleAll(alarms)
            }
        }
        val repo = TaskRepository(db.taskDao(), db.completionLogDao(), racing, db.shoppingItemDao(), clock = SystemAppClock)
        val day = LocalDate.now().plusDays(2)
        val task = repo.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        val logId = repo.logCompletion(task.id, completedAt = at(day, 7))
        assertEquals(at(day.plusDays(1), 10), marked[task.id])

        // O desfazer entra em paralelo no meio do reagendamento (com a trava, espera ele terminar; um
        // runBlocking aqui travaria, porque esta thread segura a trava).
        undoInTheMiddle = { undoJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { repo.undoCompletion(logId) } }
        repo.rescheduleReminders(System.currentTimeMillis())
        undoJob!!.join()

        assertNull(db.completionLogDao().byId(logId))
        assertEquals("desfeita, a ocorrência do dia volta a ter alarme", at(day, 10), marked[task.id])
    }


    // --- NB-E1-B: concluir e desfazer concorrendo com o reagendamento geral, nas duas ordens ---

    private class MarkingScheduler(context: Context) : ReminderScheduler(context) {
        val marked = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        override fun schedule(task: com.kalendrys.app.data.local.entity.Task, triggerAt: Long) {
            marked[task.id] = triggerAt
        }
        override fun cancel(taskId: Long) {
            marked.remove(taskId)
        }
        override fun dismissNotification(taskId: Long) = Unit
    }

    @Test
    fun `concluir e desfazer concorrendo com o catch-up terminam no alarme certo nas duas ordens`() = runTest {
        val racing = MarkingScheduler(ApplicationProvider.getApplicationContext())
        val repo = TaskRepository(db.taskDao(), db.completionLogDao(), racing, db.shoppingItemDao(), clock = SystemAppClock)
        val day = LocalDate.now().plusDays(2)
        val task = repo.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        repeat(25) { round ->
            // Ordem alternada: ora o catch-up é disparado antes, ora a gravação.
            suspend fun race(write: suspend () -> Unit) {
                val catchUp = suspend { repo.rescheduleReminders(System.currentTimeMillis()) }
                val first = if (round % 2 == 0) catchUp else write
                val second = if (round % 2 == 0) write else catchUp
                listOf(async(Dispatchers.Default) { first() }, async(Dispatchers.Default) { second() }).awaitAll()
            }
            var logId = 0L
            race { logId = repo.logCompletion(task.id, completedAt = at(day, 7)) }
            assertEquals("rodada $round: concluída, vale o dia seguinte", at(day.plusDays(1), 10), racing.marked[task.id])
            race { repo.undoCompletion(logId) }
            assertEquals("rodada $round: desfeita, volta o dia", at(day, 10), racing.marked[task.id])
        }
    }

    // --- E1: editar e restaurar (excluída com Desfazer) também pulam a ocorrência já concluída ---

    @Test
    fun `editar a diaria ja concluida no dia marca o alarme do dia seguinte`() = runTest {
        val day = LocalDate.now().plusDays(2)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        repository.logCompletion(task.id, completedAt = at(day, 7))
        scheduler.reset()

        // Mudar a hora (o que refaz o alarme): a ocorrência já concluída no dia segue pulada.
        repository.edit(task.id, TaskChange.Timing(TaskTiming(date = day, time = LocalTime.of(11, 0), repeat = ReminderRepeat.DAILY)))

        assertEquals(listOf(task.id), scheduler.cancelled)
        assertEquals(listOf(at(day.plusDays(1), 11)), scheduler.scheduled.map { it.triggerAt })
    }

    @Test
    fun `excluir e desfazer a exclusao da diaria ja concluida no dia marca o alarme do dia seguinte`() = runTest {
        val day = LocalDate.now().plusDays(2)
        val task = repository.addTask("Remédio", timing = TaskTiming(date = day, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY))
        repository.logCompletion(task.id, completedAt = at(day, 7))
        val stored = db.taskDao().byId(task.id)!!
        repository.deleteTask(stored)
        scheduler.reset()

        repository.restoreTask(TaskWithList(stored, null, emptyList()))

        assertEquals(listOf(at(day.plusDays(1), 10)), scheduler.scheduled.map { it.triggerAt })
        assertEquals(at(day.plusDays(1), 10), repository.nextReminderToDeliver())
    }
}
