package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.data.local.entity.RoutineCompletion
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.clockOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Catch-up do aviso de bloco no [RoutineRepository] (ADR-0003, 4.3.1 e 4.3.4): entrega pelo gancho,
 * marca própria, alterações que fecham a janela antes de gravar e uma execução por vez.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineRepositoryCatchUpTest {

    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var mark: InMemoryRoutineCatchUpMark
    private lateinit var repository: RoutineRepository

    /** Avisos entregues: (dia, id da rotina), na ordem. */
    private val delivered = mutableListOf<Pair<LocalDate, Long>>()

    /** Se definido, a entrega espera por ele (segura o Mutex no meio do catch-up). */
    private var deliveryGate: CompletableDeferred<Unit>? = null
    private val deliveryEntered = CompletableDeferred<Unit>()

    /** Faz a entrega lançar exceção (notificação que falha). */
    private var failDelivery = false

    /** Relógio das alterações (add, update…); o catch-up direto recebe o "agora" explícito. */
    private var clockMillis: Long = 0L

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2030, 1, 7)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarms = FakeRoutineAlarms(context)
        mark = InMemoryRoutineCatchUpMark()
        clockMillis = at(8, 0)
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            FakeRoutineNotifications(context),
            deliverDueBlocks = { routines, date ->
                deliveryEntered.complete(Unit)
                deliveryGate?.await()
                if (failDelivery) throw IllegalStateException("notificação falhou")
                routines.forEach { delivered += date to it.id }
            },
            mark = mark,
            clock = clockOf(zone = { zone }) { clockMillis },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dateTime(hour: Int, minute: Int = 0): LocalDateTime = day.atTime(hour, minute)

    private fun block(title: String, hour: Int, minute: Int = 0) =
        Routine(title = title, startMinute = hour * 60 + minute, createdAt = 0L)

    /** Cria [routine] às [hour]:[minute] (relógio das alterações) e devolve com o id. */
    private suspend fun addAt(routine: Routine, hour: Int, minute: Int = 0): Routine {
        clockMillis = at(hour, minute)
        return repository.add(routine)
    }

    // --- Entrega e agendamento ------------------------------------------------------------------

    @Test
    fun `catchUp avisa o bloco devido e agenda o proximo inicio depois de agora`() = runTest {
        val saved = addAt(block("Treino", 10), 8, 0)
        alarms.reset()

        repository.catchUp(nowOverride = at(10, 30))

        assertEquals(listOf(day to saved.id), delivered)
        assertEquals(1, alarms.calls.size)
        // Agendado a partir de agora (10:30): o aviso é o de amanhã; sem início silencioso antes dele.
        assertEquals(FakeRoutineAlarms.Call(remindAt = dateTime(10, 0).plusDays(1), refreshAt = null), alarms.calls.single())
        assertEquals(at(10, 30), mark.get())
    }

    @Test
    fun `alarme atrasado substituido ainda avisa uma vez`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)
        addAt(block("Bloco 10h", 10), 8, 0)
        repository.catchUp(nowOverride = at(8, 50))

        // Processo sobe às 9:03 com o alarme das 9:00 ainda pendente: entrega e só então reagenda.
        repository.catchUp(nowOverride = at(9, 3))
        // O alarme das 9:00 (se ainda chegar) encontra a janela vazia.
        repository.catchUp(nowOverride = at(9, 5))

        assertEquals(listOf(day to nine.id), delivered)
        assertEquals(dateTime(10, 0), alarms.calls.last().remindAt)
    }

    @Test
    fun `editar outra rotina na janela de atraso entrega o aviso antes de reagendar`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)

        addAt(block("Outra", 15), 9, 4)

        assertEquals(listOf(day to nine.id), delivered)
    }

    @Test
    fun `duas execucoes simultaneas avisam uma vez`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)
        clockMillis = at(9, 3)

        listOf(
            async { repository.catchUp() },
            async { repository.catchUp() },
            async { repository.catchUp(nowOverride = at(9, 3)) },
        ).awaitAll()

        assertEquals(listOf(day to nine.id), delivered)
    }

    @Test
    fun `bloco atrasado fora da tolerancia e descartado sem aviso`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)

        repository.catchUp(nowOverride = at(10, 1))

        assertTrue(delivered.isEmpty())
        assertEquals(at(10, 1), mark.get())
    }

    // --- Marca ----------------------------------------------------------------------------------

    @Test
    fun `primeira execucao so estabelece a marca e agenda`() = runTest {
        db.routineDao().insert(block("Bloco 9h", 9))

        repository.catchUp(nowOverride = at(9, 3))

        assertTrue(delivered.isEmpty())
        assertEquals(at(9, 3), mark.get())
        assertEquals(1, alarms.calls.size)
    }

    @Test
    fun `relogio voltou - nada e avisado e a marca passa a ser agora`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)
        mark.set(at(9, 10))

        repository.catchUp(nowOverride = at(9, 3))

        assertTrue(delivered.isEmpty())
        assertEquals(at(9, 3), mark.get())
    }

    @Test
    fun `establishBaseline grava a marca e agenda sem avisar o passado`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)
        alarms.reset()

        repository.establishBaseline(nowOverride = at(9, 3))
        repository.catchUp(nowOverride = at(9, 5))

        assertTrue(delivered.isEmpty())
        // A partir das 9:03: o bloco das 9:00 já passou, o próximo aviso é o de amanhã.
        assertEquals(dateTime(9, 0).plusDays(1), alarms.calls.first().remindAt)
    }

    // --- Alterações com horário já passado não avisam -------------------------------------------

    @Test
    fun `criar as 9h20 um bloco das 9h nao avisa`() = runTest {
        repository.catchUp(nowOverride = at(8, 50))

        addAt(block("Bloco 9h", 9), 9, 20)
        repository.catchUp(nowOverride = at(9, 25))

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `mover as 9h20 um bloco das 10h para as 9h nao avisa`() = runTest {
        val ten = addAt(block("Bloco", 10), 8, 0)

        clockMillis = at(9, 20)
        repository.edit(ten.id, RoutineChange.Start(9 * 60))
        repository.catchUp(nowOverride = at(9, 25))

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `tirar as 9h20 o SKIP de hoje do bloco das 9h nao avisa`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)
        clockMillis = at(8, 30)
        repository.skipOccurrence(nine.id, day.toEpochDay())

        clockMillis = at(9, 20)
        repository.clearOccurrenceException(nine.id, day.toEpochDay())
        repository.catchUp(nowOverride = at(9, 25))

        assertTrue(delivered.isEmpty())
        assertNull(repository.occurrenceOverride(nine.id, day.toEpochDay()))
    }

    @Test
    fun `retomar as 9h20 uma rotina pausada do bloco das 9h nao avisa`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)
        clockMillis = at(8, 30)
        repository.pause(nine, now = at(8, 30))

        clockMillis = at(9, 20)
        repository.resume(nine.copy(pausedAt = at(8, 30)))
        repository.catchUp(nowOverride = at(9, 25))

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `desfazer as 9h20 a conclusao do bloco das 9h nao avisa`() = runTest {
        val nine = addAt(block("Bloco 9h", 9), 8, 0)
        clockMillis = at(8, 55)
        repository.complete(nine.id, day, today = day)

        clockMillis = at(9, 20)
        repository.uncomplete(nine.id, day)
        repository.catchUp(nowOverride = at(9, 25))

        assertTrue(delivered.isEmpty())
    }

    // --- Exceções no catch-up (migrados do antigo onAlarm) --------------------------------------

    @Test
    fun `ocorrencia pulada nao e avisada e o agendamento recebe as excecoes`() = runTest {
        val saved = addAt(block("Treino", 10), 8, 0)
        clockMillis = at(9, 0)
        repository.skipOccurrence(saved.id, day.toEpochDay())
        repository.skipOccurrence(saved.id, day.plusDays(1).toEpochDay())
        alarms.reset()

        repository.catchUp(nowOverride = at(10, 30))

        assertTrue("a ocorrência pulada não é devida", delivered.isEmpty())
        assertEquals(1, alarms.calls.size)
        // O agendamento honra as exceções: amanhã também foi pulado, o aviso é o de depois de amanhã.
        assertEquals(dateTime(10, 0).plusDays(2), alarms.calls.single().remindAt)
    }

    @Test
    fun `remarcar o minuto muda o horario do aviso`() = runTest {
        val saved = addAt(block("Treino", 10), 8, 0)
        clockMillis = at(9, 0)
        repository.rescheduleOccurrence(saved.id, day.toEpochDay(), newStartMinute = 11 * 60, newEpochDay = null)

        // Às 10:30 o horário base (10:00) já passou, mas o remarcado (11:00) ainda não.
        repository.catchUp(nowOverride = at(10, 30))
        assertTrue(delivered.isEmpty())

        repository.catchUp(nowOverride = at(11, 30))
        assertEquals(listOf(day to saved.id), delivered)
    }

    @Test
    fun `limpar a excecao antes do horario volta o aviso ao normal`() = runTest {
        val saved = addAt(block("Treino", 10), 8, 0)
        clockMillis = at(9, 0)
        repository.skipOccurrence(saved.id, day.toEpochDay())
        repository.clearOccurrenceException(saved.id, day.toEpochDay())

        repository.catchUp(nowOverride = at(10, 30))

        assertEquals(listOf(day to saved.id), delivered)
    }

    @Test
    fun `bloco concluido antes do horario nao e avisado`() = runTest {
        val saved = addAt(block("Treino", 10), 8, 0)
        db.routineCompletionDao().insert(RoutineCompletion(routineId = saved.id, epochDay = day.toEpochDay(), completedAt = TEST_CREATED_AT))

        repository.catchUp(nowOverride = at(10, 30))

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `rotina com aviso concluida pelo app antes do inicio nao e entregue quando o alarme toca`() = runTest {
        // D-71-1, item c: o mesmo caminho do app (Concluir às 8:00, alarme do início às 10:00 chama o catchUp).
        val saved = addAt(block("Treino", 10).copy(remind = true), 8, 0)
        clockMillis = at(8, 0)
        repository.complete(saved.id, day, today = day)

        repository.catchUp(nowOverride = at(10, 0))

        assertTrue(delivered.isEmpty())
        assertEquals(at(10, 0), mark.get())
    }

    @Test
    fun `concluir as 9h02 o bloco das 9h com alarme atrasado avisa uma vez antes de gravar e nao repete`() = runTest {
        // Comportamento documentado: o catch-up antes de gravar entrega o aviso atrasado (com a
        // configuração antiga) e o complete o cancela logo em seguida; o alarme que chegar depois
        // encontra a janela vazia e o bloco concluído.
        val saved = addAt(block("Bloco 9h", 9), 8, 0)
        repository.catchUp(nowOverride = at(8, 50))
        clockMillis = at(9, 2)

        repository.complete(saved.id, day, today = day)

        assertEquals(listOf(day to saved.id), delivered)
        assertEquals(at(9, 2), mark.get())
        assertEquals(listOf(saved.id), db.routineCompletionDao().doneOn(day.toEpochDay()))
        repository.catchUp(nowOverride = at(9, 5)) // o alarme atrasado chega depois
        assertEquals(listOf(day to saved.id), delivered)
    }

    @Test
    fun `arquivar na janela de atraso nao reavisa o bloco depois`() = runTest {
        val saved = addAt(block("Bloco 9h", 9), 8, 0)
        repository.catchUp(nowOverride = at(8, 50))
        clockMillis = at(9, 2)

        repository.archive(saved, now = at(9, 2))
        repository.catchUp(nowOverride = at(9, 10))

        assertEquals("só o catch-up antes de arquivar entrega", listOf(day to saved.id), delivered)
    }

    // --- Robustez das alterações (parecer do Esquadro, NB-1 e NB-2 do 4.3) ------------------------

    @Test
    fun `cancelar no meio do salvar ainda grava e agenda a nova configuracao`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)
        val other = addAt(block("Outro", 11), 8, 0)
        repository.catchUp(nowOverride = at(8, 30))
        deliveryGate = CompletableDeferred()
        clockMillis = at(9, 3) // o bloco das 9:00 está na janela: a entrega segura o salvar
        alarms.reset()
        withContext(Dispatchers.Default) {
            val save = launch { repository.edit(other.id, RoutineChange.Start(12 * 60)) }
            deliveryEntered.await()
            val leave = launch { save.cancelAndJoin() } // "Salvar" e voltar na hora
            deliveryGate!!.complete(Unit)
            leave.join()
        }
        assertEquals(12 * 60, db.routineDao().byId(other.id)?.startMinute)
        assertEquals(1, alarms.calls.size)
        // O agendamento já vê a linha nova: o próximo aviso é o das 12:00.
        assertEquals(dateTime(12, 0), alarms.calls.single().remindAt)
    }

    @Test
    fun `falha na entrega nao impede salvar a rotina`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)
        val other = addAt(block("Outro", 11), 8, 0)
        repository.catchUp(nowOverride = at(8, 30))
        failDelivery = true
        clockMillis = at(9, 3)
        alarms.reset()

        // Mudança de agenda (passa pelo catch-up, v1.9.0): a entrega falha e a gravação segue.
        repository.edit(other.id, RoutineChange.Start(12 * 60))

        assertEquals(12 * 60, db.routineDao().byId(other.id)?.startMinute)
        assertEquals(1, alarms.calls.size)
        assertEquals(at(9, 3), mark.get())
    }

    @Test
    fun `falha na entrega propaga no catchUp dos pontos de entrada`() = runTest {
        addAt(block("Bloco 9h", 9), 8, 0)
        repository.catchUp(nowOverride = at(8, 30))
        failDelivery = true
        try {
            repository.catchUp(nowOverride = at(9, 3))
            fail("esperava a falha")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(at(8, 30), mark.get())
    }
}
