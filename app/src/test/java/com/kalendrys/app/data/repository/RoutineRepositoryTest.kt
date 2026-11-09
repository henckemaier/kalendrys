package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import java.time.LocalDate
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
 * Sincronia banco ↔ alarme/notificação no [RoutineRepository]: cada operação ajusta o único
 * alarme do próximo início ([com.kalendrys.app.reminder.RoutineAlarms]) e as notificações.
 * Usa Room em memória + coroutines-test + Turbine, com fakes dos colaboradores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var notifications: FakeRoutineNotifications
    private lateinit var repository: RoutineRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarms = FakeRoutineAlarms(context)
        notifications = FakeRoutineNotifications(context)
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            alarms,
            notifications,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun routine(title: String = "Treino", startMinute: Int = 600) =
        Routine(title = title, startMinute = startMinute)

    @Test
    fun `routineForNotification devolve o bloco ativo que ocorre hoje e nao foi concluido`() = runTest {
        val saved = repository.add(routine())

        assertNotNull(repository.routineForNotification(saved.id, LocalDate.now()))
    }

    @Test
    fun `routineForNotification e null para bloco ja concluido (idempotencia do Adiar)`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()
        repository.complete(saved.id, today)

        assertNull(repository.routineForNotification(saved.id, today))
    }

    @Test
    fun `routineForNotification e null para rotina arquivada`() = runTest {
        val saved = repository.add(routine())
        repository.archive(saved)

        assertNull(repository.routineForNotification(saved.id, LocalDate.now()))
    }

    @Test
    fun `routineForNotification e null para rotina inexistente`() = runTest {
        assertNull(repository.routineForNotification(routineId = 999, date = LocalDate.now()))
    }

    @Test
    fun `routineForNotification e null quando a ocorrencia foi pulada enquanto adiada`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()
        // Adiar (snooze) e, enquanto o aviso dormia, pular a MESMA ocorrência pela tela de exceção.
        repository.skipOccurrence(saved.id, today.toEpochDay())

        assertNull(repository.routineForNotification(saved.id, today))
    }

    @Test
    fun `routineForNotification e null quando a ocorrencia foi remarcada enquanto adiada`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()
        // Remarcada no mesmo dia: ainda "ocorre" hoje, mas o aviso adiado é do horário ANTIGO (stale),
        // então não reexibe — o agendamento normal cuida do novo horário.
        repository.rescheduleOccurrence(saved.id, today.toEpochDay(), newStartMinute = 660, newEpochDay = null)

        assertNull(repository.routineForNotification(saved.id, today))
    }

    @Test
    fun `pausar cancela a notificacao, grava pausedAt e reagenda sem a rotina`() = runTest {
        val saved = repository.add(routine())
        alarms.reset()

        repository.pause(saved, now = 123L)

        assertEquals(listOf(saved.id), notifications.cancelled)
        assertEquals(123L, db.routineDao().byId(saved.id)?.pausedAt)
        assertEquals(1, alarms.calls.size)
        assertTrue(
            "a rotina pausada sai das ativas e não entra no próximo alarme",
            alarms.calls.last().routines.none { it.id == saved.id },
        )
    }

    @Test
    fun `retomar limpa pausedAt e reagenda com a rotina de volta`() = runTest {
        val saved = repository.add(routine())
        repository.pause(saved)
        alarms.reset()

        repository.resume(db.routineDao().byId(saved.id)!!)

        assertNull(db.routineDao().byId(saved.id)?.pausedAt)
        assertEquals(1, alarms.calls.size)
        assertTrue(alarms.calls.last().routines.any { it.id == saved.id })
    }

    @Test
    fun `scheduledRoutines exclui as pausadas e mantem as ativas`() = runTest {
        val ativa = repository.add(routine("Ativa"))
        val pausada = repository.add(routine("Pausada"))
        repository.pause(pausada)

        repository.scheduledRoutines.test {
            val list = awaitItem()
            assertTrue(list.any { it.id == ativa.id })
            assertTrue(list.none { it.id == pausada.id })
        }
    }

    @Test
    fun `pausar preserva o historico de conclusoes`() = runTest {
        val saved = repository.add(routine())
        repository.complete(saved.id, LocalDate.now())

        repository.pause(saved)

        assertEquals(1, db.routineCompletionDao().count())
    }

    @Test
    fun `adicionar rotina agenda o proximo alarme`() = runTest {
        val saved = repository.add(routine())

        assertEquals(1, alarms.calls.size)
        assertTrue(alarms.calls.last().routines.any { it.id == saved.id })
    }

    @Test
    fun `editar rotina reagenda o proximo alarme`() = runTest {
        val saved = repository.add(routine())
        alarms.reset()

        repository.update(saved.copy(title = "Treino da manhã"))

        assertEquals(1, alarms.calls.size)
    }

    @Test
    fun `arquivar rotina cancela a notificacao e reagenda sem ela`() = runTest {
        val saved = repository.add(routine())
        alarms.reset()

        repository.archive(saved)

        assertEquals(listOf(saved.id), notifications.cancelled)
        assertEquals(1, alarms.calls.size)
        assertTrue(
            "a rotina arquivada sai das ativas e não entra no próximo alarme",
            alarms.calls.last().routines.none { it.id == saved.id },
        )
    }

    @Test
    fun `concluir hoje registra a conclusao e cancela a notificacao`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()

        repository.complete(saved.id, today)

        assertTrue(notifications.cancelled.contains(saved.id))
        repository.observeCompletionsBetween(today, today.plusDays(1)).test {
            assertEquals(listOf(saved.id), awaitItem().map { it.routineId })
        }
    }

    @Test
    fun `concluir a mesma ocorrencia duas vezes e idempotente`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()

        repository.complete(saved.id, today)
        repository.complete(saved.id, today)

        assertEquals(1, db.routineCompletionDao().count())
    }

    @Test
    fun `concluir rotina inexistente nao faz nada`() = runTest {
        repository.complete(routineId = 999, date = LocalDate.now())

        assertEquals(0, db.routineCompletionDao().count())
        assertTrue(notifications.cancelled.isEmpty())
    }

    @Test
    fun `onAlarm agenda o proximo inicio e devolve os blocos devidos`() = runTest {
        val saved = repository.add(routine(startMinute = 600)) // 10:00
        alarms.reset()
        val today = LocalDate.now()

        val due = repository.onAlarm(
            epochDay = today.toEpochDay(),
            scheduledMinute = 600,
            now = today.atTime(11, 0),
        )

        assertEquals(listOf(saved.id), due.map { it.id })
        assertEquals(1, alarms.calls.size)
    }

    @Test
    fun `pular ocorrencia remove o bloco do onAlarm daquele dia`() = runTest {
        val saved = repository.add(routine(startMinute = 600))
        val today = LocalDate.now()
        repository.skipOccurrence(saved.id, today.toEpochDay())
        alarms.reset()

        val due = repository.onAlarm(epochDay = today.toEpochDay(), scheduledMinute = 600, now = today.atTime(11, 0))

        assertTrue("a ocorrência pulada não é devida", due.isEmpty())
        // O alarme é reagendado já passando as exceções adiante.
        assertEquals(1, alarms.calls.size)
        assertEquals(RoutineOverrideType.SKIP, repository.occurrenceOverride(saved.id, today.toEpochDay())?.type)
    }

    @Test
    fun `remarcar o minuto muda o horario devido no onAlarm`() = runTest {
        val saved = repository.add(routine(startMinute = 600)) // 10:00
        val today = LocalDate.now()
        repository.rescheduleOccurrence(saved.id, today.toEpochDay(), newStartMinute = 660, newEpochDay = null) // 11:00

        // Às 10:30 (horário antigo 10:00 já passou, mas o remarcado 11:00 ainda não) não é devida;
        // às 11:30, no horário remarcado, sim. O horário efetivo é o remarcado, não o base.
        val atOld = repository.onAlarm(epochDay = today.toEpochDay(), scheduledMinute = 600, now = today.atTime(10, 30))
        val atNew = repository.onAlarm(epochDay = today.toEpochDay(), scheduledMinute = 660, now = today.atTime(11, 30))

        assertTrue("antes do horário remarcado não há bloco devido", atOld.none { it.id == saved.id })
        assertEquals(listOf(saved.id), atNew.map { it.id })
    }

    @Test
    fun `limpar a excecao volta a ocorrencia ao normal`() = runTest {
        val saved = repository.add(routine(startMinute = 600))
        val today = LocalDate.now()
        repository.skipOccurrence(saved.id, today.toEpochDay())
        repository.clearOccurrenceException(saved.id, today.toEpochDay())

        val due = repository.onAlarm(epochDay = today.toEpochDay(), scheduledMinute = 600, now = today.atTime(11, 0))

        assertEquals(listOf(saved.id), due.map { it.id })
        assertEquals(null, repository.occurrenceOverride(saved.id, today.toEpochDay()))
    }
}
