package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.testutil.FakeReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun `falha no gancho beforeReminderChange nao impede gravar a tarefa`() = runTest {
        // A notificação do catch-up falhou: perder a gravação seria pior (parecer do Esquadro, NB-2).
        val failing = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao()) {
            throw IllegalStateException("notificação falhou")
        }
        val task = failing.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        failing.updateTask(task.copy(title = "Treino forte"))
        val logId = failing.logCompletion(task.id)
        failing.undoCompletion(logId)
        failing.deleteTask(task.copy(title = "Treino forte"))
        failing.restoreTask(task.copy(title = "Treino forte"))
        assertEquals(listOf("Treino forte"), db.taskDao().all().map { it.title })
        assertEquals(0, db.completionLogDao().count())
    }

    @Test
    fun `gancho beforeReminderChange roda antes de gravar em add update e restore`() = runTest {
        // O gancho vê o banco ANTES da escrita: é o que fecha a janela do catch-up (ADR-0003).
        val seen = mutableListOf<List<String>>()
        val hooked = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao()) {
            seen += db.taskDao().all().map { it.title }
        }
        val task = hooked.addTask("Treino", timing = todayTiming(ReminderRepeat.DAILY))
        hooked.updateTask(task.copy(title = "Treino forte"))
        hooked.deleteTask(task.copy(title = "Treino forte"))
        hooked.restoreTask(task.copy(title = "Treino forte"))
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
        repository.restoreTask(current)
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
        val listId = listWithPickedItems()
        val tomorrow = repository.addTask("Amanhã", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)))
        val daily = repository.addTask("Diária", timing = todayTiming(ReminderRepeat.DAILY))
        repository.updateTask(db.taskDao().byId(tomorrow.id)!!.copy(shoppingListId = listId))
        repository.updateTask(db.taskDao().byId(daily.id)!!.copy(shoppingListId = listId))
        repository.logCompletion(daily.id)

        assertEquals(
            mapOf(tomorrow.id to true, daily.id to false),
            repository.linkedTasks(listId).associate { it.taskId to it.pending },
        )
    }

    @Test
    fun `tarefa unica de amanha ja concluida nao conta como pendente`() = runTest {
        val listId = listWithPickedItems()
        val tomorrow = repository.addTask("Amanhã", timing = TaskTiming(date = LocalDate.now().plusDays(1), time = LocalTime.of(9, 0)))
        repository.updateTask(db.taskDao().byId(tomorrow.id)!!.copy(shoppingListId = listId))
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
        repository.updateTask(db.taskDao().byId(task.id)!!.copy(dueDate = edited))

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
        repository.updateTask(completed.copy(reminderAt = newReminder))

        repository.undoCompletion(logId)

        val after = db.taskDao().byId(task.id)!!
        assertEquals(LocalDate.now().toEpochDay(), after.dueDate)
        assertEquals(newReminder, after.reminderAt)
    }

    @Test
    fun `tarefa unica adiantada com lista nao zera e desfazer devolve a data sem mexer na lista`() = runTest {
        val listId = listWithPickedItems()
        val task = repository.addTask("Mercado amanhã", timing = tomorrowAtNine(), shoppingListId = listId)
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
        val fresh = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao())

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
        repository.updateTask(db.taskDao().byId(task.id)!!.copy(dueDate = LocalDate.now().plusDays(3).toEpochDay(), reminderAt = null))
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
}
