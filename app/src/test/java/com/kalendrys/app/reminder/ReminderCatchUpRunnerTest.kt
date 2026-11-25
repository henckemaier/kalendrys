package com.kalendrys.app.reminder

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.CompletionLog
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskChange
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.clockOf
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Runner do catch-up de lembretes (ADR-0003, seção 6): Room em memória, DataStore real em arquivo
 * temporário, agendador e notificador falsos que registram a ORDEM das chamadas num diário comum.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderCatchUpRunnerTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = LocalDate.of(2030, 1, 7)

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var scheduler: LoggingScheduler
    private lateinit var notifier: FakeNotifier
    private lateinit var repository: TaskRepository
    private lateinit var runner: ReminderCatchUpRunner

    /** Diário comum de agendador e notificador, para conferir a ordem entrega → reagenda. */
    private val log = mutableListOf<String>()

    /** Relógio do runner e do gancho do repositório. */
    private var clock = 0L

    private class LoggingScheduler(context: Context, private val log: MutableList<String>) : ReminderScheduler(context) {
        var failNextReschedule = false
        val cancelled = mutableListOf<Long>()

        override fun schedule(task: Task, triggerAt: Long) {
            log += "schedule:${task.id}"
        }

        override fun cancel(taskId: Long) {
            cancelled += taskId
        }

        override fun dismissNotification(taskId: Long) = Unit

        override fun rescheduleAll(alarms: List<ReminderAlarm>) {
            if (failNextReschedule) {
                failNextReschedule = false
                throw IllegalStateException("processo morreu antes da marca")
            }
            log += "reschedule:" + alarms.map { it.task.id }.sorted().joinToString(",")
        }
    }

    private class FakeNotifier(private val log: MutableList<String>) : ReminderCatchUpRunner.Notifier {
        val shown = mutableListOf<Long>()

        /** O instante da OCORRÊNCIA que cada entrega recebeu (NB-E5-B): é dele que sai o dia da notificação. */
        val firedAt = mutableListOf<Long>()
        val missed = mutableListOf<Int>()

        /** Se definido, a entrega espera por ele (segura o Mutex no meio da execução). */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun showReminder(task: Task, firedAt: Long) {
            entered.complete(Unit)
            gate?.await()
            shown += task.id
            this.firedAt += firedAt
            log += "show:${task.id}"
        }

        override fun showMissed(count: Int) {
            missed += count
            log += "missed:$count"
        }

        override suspend fun remindersChanged() = Unit
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create { File(context.cacheDir, "catch-up-${System.nanoTime()}.preferences_pb") },
        )
        scheduler = LoggingScheduler(context, log)
        notifier = FakeNotifier(log)
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), scheduler, db.shoppingItemDao(), clock = SystemAppClock) {
            runner.run(clock)
        }
        runner = ReminderCatchUpRunner(repository, settings, notifier, clock = clockOf(zone = { zone }) { clock })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private suspend fun insertTask(
        hour: Int,
        minute: Int = 0,
        repeat: ReminderRepeat = ReminderRepeat.NONE,
        title: String = "Tarefa",
        date: LocalDate = day,
    ): Task {
        val task = Task(title = title, dueDate = date.toEpochDay(), reminderAt = at(hour, minute, date), repeat = repeat)
        return task.copy(id = db.taskDao().insert(task))
    }

    private suspend fun seenAt(millis: Long) {
        settings.setLastSeen(millis)
        log.clear()
    }

    @Test
    fun `ocorrencia de ontem 23h50 entregue hoje chega com o instante dela, e o dia e o de ontem`() = runTest {
        // NB-E5-B da revisão do Lince: o runner passa o firedAt da ocorrência, não o agora.
        val yesterday = day.minusDays(1)
        val task = insertTask(23, 50, repeat = ReminderRepeat.DAILY, date = yesterday.minusDays(3))
        seenAt(at(23, 40, yesterday))

        runner.run(at(0, 10)) // abriu o app às 0:10 de hoje: a das 23:50 de ontem está na tolerância

        assertEquals(listOf(task.id), notifier.shown)
        assertEquals(listOf(at(23, 50, yesterday)), notifier.firedAt)
        assertEquals(yesterday, java.time.Instant.ofEpochMilli(notifier.firedAt.single()).atZone(zone).toLocalDate())
    }

    @Test
    fun `primeira execucao so estabelece a marca e agenda`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        runner.run(at(9, 3))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
        assertEquals(listOf("reschedule:${task.id}"), log)
        assertEquals(at(9, 3), settings.lastSeenAt())
    }

    @Test
    fun `app abre na janela de atraso mostra uma vez agenda a proxima e o alarme atrasado nao duplica`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        runner.run(at(9, 3)) // abriu o app às 9:03, alarme das 9:00 ainda pendente
        // Entrega ANTES de reagendar (sem pulo) e só depois grava a marca.
        assertEquals(listOf("show:${task.id}", "reschedule:${task.id}"), log)
        assertEquals(at(9, 3), settings.lastSeenAt())

        runner.run(at(9, 8)) // o alarme atrasado chega depois
        assertEquals(listOf(task.id), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `alarme chega primeiro e o app abre depois sem perdido`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        runner.run(at(9, 0) + 500) // alarme na hora
        runner.run(at(11)) // app aberto bem depois
        assertEquals(listOf(task.id), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `tarefa A atrasada e B no horario A nao some`() = runTest {
        val a = insertTask(9, title = "A")
        val b = insertTask(9, 5, title = "B")
        seenAt(at(8))
        runner.run(at(9, 5)) // o alarme de B chega antes do de A, que atrasou
        assertEquals(listOf(a.id, b.id), notifier.shown)
        runner.run(at(9, 9)) // o de A chega agora
        assertEquals(listOf(a.id, b.id), notifier.shown)
    }

    @Test
    fun `inicio de processo nao pula a ocorrencia de hoje`() = runTest {
        // Antes do ADR-0003, o onCreate reagendava direto: o alarme das 9:00 virava o de amanhã.
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8, 59))
        runner.run(at(9, 2)) // KalendrysApp.onCreate
        val show = log.indexOf("show:${task.id}")
        val reschedule = log.indexOf("reschedule:${task.id}")
        assertTrue("entregou", show >= 0)
        assertTrue("reagendou só depois de entregar", reschedule > show)
    }

    @Test
    fun `duas execucoes simultaneas entregam uma vez`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        withContext(Dispatchers.Default) {
            listOf(at(9, 3), at(9, 3) + 1, at(9, 3) + 2).map { now -> async { runner.run(now) } }.awaitAll()
        }
        assertEquals(listOf(task.id), notifier.shown)
    }

    @Test
    fun `falha antes de gravar a marca reexibe e nao perde`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        scheduler.failNextReschedule = true
        try {
            runner.run(at(9, 3))
            fail("esperava a falha")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(at(8), settings.lastSeenAt())
        runner.run(at(9, 4))
        assertEquals(listOf(task.id, task.id), notifier.shown) // mesma tarefa, mesmo id de notificação
        assertEquals(at(9, 4), settings.lastSeenAt())
    }

    @Test
    fun `criar tarefa com horario ja passado nao mostra nada`() = runTest {
        seenAt(at(8))
        clock = at(10)
        repository.addTask("Diária", timing = TaskTiming(date = day, time = LocalTime.of(9, 0), repeat = ReminderRepeat.DAILY))
        repository.addTask("Única", timing = TaskTiming(date = day, time = LocalTime.of(9, 30)))
        runner.run(at(10, 5))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `editar tarefa para horario ja passado nao mostra nada`() = runTest {
        val task = insertTask(11)
        seenAt(at(8))
        clock = at(10)
        val nine = java.time.Instant.ofEpochMilli(at(9)).atZone(java.time.ZoneId.systemDefault())
        repository.edit(task.id, TaskChange.Timing(TaskTiming.of(task, java.time.ZoneId.systemDefault()).copy(date = nine.toLocalDate(), time = nine.toLocalTime())))
        runner.run(at(10, 5))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `restaurar tarefa excluida com horario ja passado nao mostra nada`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        repository.deleteTask(task) // às 8:30, antes do lembrete
        clock = at(10)
        repository.restoreTask(task) // "Desfazer" às 10:00
        runner.run(at(10, 5))
        assertEquals(emptyList<Long>(), notifier.shown)
    }

    @Test
    fun `restauro de backup grava a marca e so agenda`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY, date = day.minusDays(5))
        seenAt(at(8, date = day.minusDays(3))) // marca antiga: três dias de ocorrências no passado
        runner.establishBaseline(at(9, 10))
        assertEquals(listOf("reschedule:${task.id}"), log)
        assertEquals(at(9, 10), settings.lastSeenAt())
        runner.run(at(9, 15))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `relogio voltou janela vazia e a marca passa a ser agora`() = runTest {
        insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(10))
        runner.run(at(9, 30))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(at(9, 30), settings.lastSeenAt())
    }

    @Test
    fun `perdidas fora da tolerancia viram o agregado`() = runTest {
        insertTask(9, repeat = ReminderRepeat.DAILY, date = day.minusDays(5))
        insertTask(7)
        seenAt(at(8, date = day.minusDays(2))) // desligado desde anteontem 8:00
        runner.run(at(12))
        // Diária: anteontem, ontem e hoje (9:00, 3h atrás); única das 7:00 de hoje.
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(listOf(4), notifier.missed)
    }

    @Test
    fun `tarefa unica atrasada tem o alarme pendente cancelado e repetida concluida hoje fica de fora`() = runTest {
        val once = insertTask(9)
        val daily = insertTask(9, repeat = ReminderRepeat.DAILY)
        db.completionLogDao().insert(CompletionLog(taskId = daily.id, completedAt = at(8, 30)))
        seenAt(at(8))
        runner.run(at(9, 3))
        assertEquals(listOf(once.id), notifier.shown)
        assertEquals(listOf(once.id), scheduler.cancelled)
    }

    @Test
    fun `agora e lido dentro do Mutex e chamadas em ordem trocada nao gravam a marca para tras`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        notifier.gate = CompletableDeferred()
        withContext(Dispatchers.Default) {
            // A entra primeiro com 9:05 e segura o Mutex na entrega.
            val first = launch { runner.run(at(9, 5)) }
            notifier.entered.await()
            // B é chamado enquanto o relógio marca 9:04 (antes de A gravar a marca)...
            clock = at(9, 4)
            // UNDISPATCHED: B roda na hora até suspender no Mutex (o que leria o relógio antes, lê agora).
            val second = launch(start = CoroutineStart.UNDISPATCHED) { runner.run() }
            // ...mas só entra depois de A, quando o relógio já marca 9:06.
            clock = at(9, 6)
            notifier.gate!!.complete(Unit)
            first.join()
            second.join()
        }
        assertEquals(at(9, 6), settings.lastSeenAt())
        assertEquals(listOf(task.id), notifier.shown)
    }

    @Test
    fun `cancelar no meio da entrega ainda reagenda e grava a marca sem reexibir`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        notifier.gate = CompletableDeferred()
        withContext(Dispatchers.Default) {
            val job = launch { runner.run(at(9, 3)) } // ex.: lifecycleScope da MainActivity
            notifier.entered.await()
            val cancel = launch { job.cancelAndJoin() } // a tela fecha no meio da entrega
            notifier.gate!!.complete(Unit)
            cancel.join()
        }
        assertEquals(at(9, 3), settings.lastSeenAt())
        assertEquals(listOf("show:${task.id}", "reschedule:${task.id}"), log)
        notifier.gate = null
        runner.run(at(9, 4))
        assertEquals(listOf(task.id), notifier.shown)
    }

    @Test
    fun `desfazer conclusao de tarefa unica depois do horario nao gera atrasado nem perdido`() = runTest {
        val task = insertTask(9)
        seenAt(at(8))
        clock = at(8, 50)
        val logId = repository.logCompletion(task.id, completedAt = at(8, 50))
        // O alarme das 9:00 atrasou e ainda não chegou: nenhum catch-up entre 9:00 e o desfazer.
        clock = at(9, 10)
        repository.undoCompletion(logId) // "Desfazer" às 9:10
        runner.run(at(9, 15))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
    }

    @Test
    fun `desfazer conclusao de tarefa repetida depois do horario nao gera atrasado nem perdido`() = runTest {
        val task = insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        clock = at(8, 50)
        val logId = repository.logCompletion(task.id, completedAt = at(8, 50))
        // Alarme das 9:00 atrasado; o "Desfazer" às 9:10 fecha a janela com a tarefa ainda concluída hoje.
        clock = at(9, 10)
        repository.undoCompletion(logId)
        runner.run(at(9, 15))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
        assertTrue("a próxima ocorrência continua agendada", log.contains("reschedule:${task.id}"))
    }

    @Test
    fun `alarme atrasado de tarefa ja excluida nao mostra nada e avanca a marca`() = runTest {
        val task = insertTask(9)
        seenAt(at(8))
        clock = at(8, 30)
        repository.deleteTask(task)
        runner.run(at(9, 2)) // o alarme atrasado acorda o app depois da exclusão
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(emptyList<Int>(), notifier.missed)
        assertEquals(at(9, 2), settings.lastSeenAt())
    }

    @Test
    fun `perdida fora da tolerancia nao e recontada na reabertura seguinte`() = runTest {
        // Aparelho desligado das 8:00 às 11:00: a das 9:00 vira uma perdida; reabrir às 11:05 não reconta.
        insertTask(9, repeat = ReminderRepeat.DAILY)
        seenAt(at(8))
        runner.run(at(11))
        runner.run(at(11, 5))
        assertEquals(emptyList<Long>(), notifier.shown)
        assertEquals(listOf(1), notifier.missed)
    }
}
