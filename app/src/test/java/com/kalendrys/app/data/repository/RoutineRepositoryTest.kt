package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.TEST_CREATED_AT
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
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun routine(title: String = "Treino", startMinute: Int = 600) =
        Routine(title = title, startMinute = startMinute, createdAt = TEST_CREATED_AT)

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
    fun `routineForNotification devolve a propria ocorrencia remarcada no dia (snooze mantem o lembrete)`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()
        // A ocorrência JÁ É a remarcada (só mudou o minuto, continua hoje): "Adiar" nela tem de
        // reexibir o aviso, senão o lembrete some em silêncio (A1/A2 do T36).
        repository.rescheduleOccurrence(saved.id, today.toEpochDay(), newStartMinute = 660, newEpochDay = null)

        assertNotNull(repository.routineForNotification(saved.id, today))
    }

    @Test
    fun `routineForNotification e null quando a ocorrencia foi remarcada para OUTRO dia`() = runTest {
        val saved = repository.add(routine())
        val today = LocalDate.now()
        // Remarcada para outro dia: hoje o disparo ficou obsoleto (o alarme do novo dia cuida dela),
        // então o aviso adiado não reexibe aqui. Não regride o filtro legítimo.
        repository.rescheduleOccurrence(
            saved.id,
            today.toEpochDay(),
            newStartMinute = null,
            newEpochDay = today.plusDays(1).toEpochDay(),
        )

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
        assertEquals(
            "a rotina pausada sai das ativas: os dois alarmes são cancelados",
            FakeRoutineAlarms.Call(remindAt = null, refreshAt = null),
            alarms.calls.last(),
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
        assertEquals(10, alarms.calls.last().remindAt?.hour)
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
        repository.add(routine())

        assertEquals(1, alarms.calls.size)
        assertEquals(10, alarms.calls.last().remindAt?.hour)
    }

    @Test
    fun `editar o horario da rotina reagenda o proximo alarme`() = runTest {
        val saved = repository.add(routine())
        alarms.reset()

        repository.edit(saved.id, RoutineChange.Start(7 * 60))

        assertEquals(1, alarms.calls.size)
    }

    @Test
    fun `arquivar rotina cancela a notificacao e reagenda sem ela`() = runTest {
        val saved = repository.add(routine())
        alarms.reset()

        repository.archive(saved)

        assertEquals(listOf(saved.id), notifications.cancelled)
        assertEquals(1, alarms.calls.size)
        assertEquals(
            "a rotina arquivada sai das ativas: os dois alarmes são cancelados",
            FakeRoutineAlarms.Call(remindAt = null, refreshAt = null),
            alarms.calls.last(),
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

    // --- v1.10.0 (ADR-0002 16.10.1): rotina não tem lista; concluir não mexe em lista nenhuma ---

    @Test
    fun `concluir e desfazer rotina com vinculo antigo no banco nao mexem na lista`() = runTest {
        // Um vínculo que tenha escapado da migração (linha gravada direto): ignorado.
        val shopping = ShoppingRepository(db, clock = SystemAppClock)
        val listId = shopping.createList("Mercado")
        shopping.add(listId, "Arroz", 1, null)
        db.shoppingItemDao().byList(listId).forEach { shopping.setPicked(it, true) }
        val id = db.routineDao().insert(Routine(title = "Mercado", startMinute = 600, shoppingListId = listId, createdAt = TEST_CREATED_AT))
        val today = LocalDate.now()

        repository.complete(id, today, today)
        assertEquals(listOf(true), db.shoppingItemDao().byList(listId).map { it.picked })
        assertNull(db.shoppingListDao().byId(listId)!!.lastResetKey)
        repository.uncomplete(id, today)
        assertEquals(listOf(true), db.shoppingItemDao().byList(listId).map { it.picked })
    }

    @Test
    fun `concluir a rotina no dia do proximo aviso o adia e deixa o silencioso, desfazer volta`() = runTest {
        val saved = repository.add(routine(startMinute = 600).copy(remind = true))
        val next = checkNotNull(alarms.calls.last().remindAt)
        assertNull("o próximo início já é o do aviso: sem o silencioso", alarms.calls.last().refreshAt)

        repository.complete(saved.id, next.toLocalDate())
        // Concluída no dia: o aviso passa ao próximo início, e o desse dia só redesenha os widgets.
        val done = alarms.calls.last()
        assertTrue("o aviso vai para depois", done.remindAt!! > next)
        assertEquals(next, done.refreshAt)

        repository.uncomplete(saved.id, next.toLocalDate())
        assertEquals(FakeRoutineAlarms.Call(remindAt = next, refreshAt = null), alarms.calls.last())
    }

    // --- P5 (v1.8.0, E3): pular devolve a exceção anterior; o Desfazer da mensagem a recoloca ---

    @Test
    fun `pular devolve a excecao que substituiu e restaurar a recoloca, reagendando`() = runTest {
        val saved = repository.add(routine())
        val epochDay = LocalDate.now().toEpochDay()
        repository.rescheduleOccurrence(saved.id, epochDay, newStartMinute = 930, newEpochDay = null)

        val previous = checkNotNull(repository.skipOccurrence(saved.id, epochDay)).previous
        assertEquals(RoutineOverrideType.SKIP, repository.occurrenceOverride(saved.id, epochDay)?.type)
        assertEquals(RoutineOverrideType.RESCHEDULE, previous?.type)

        val callsBefore = alarms.calls.size
        repository.restoreOccurrenceException(saved.id, epochDay, previous)
        val restored = repository.occurrenceOverride(saved.id, epochDay)
        assertEquals(RoutineOverrideType.RESCHEDULE, restored?.type)
        assertEquals(930, restored?.newStartMinute)
        assertTrue("restaurar reagenda", alarms.calls.size > callsBefore)
    }

    @Test
    fun `pular sem excecao devolve null e pular de novo nao devolve o proprio SKIP`() = runTest {
        val saved = repository.add(routine())
        val epochDay = LocalDate.now().toEpochDay()

        assertNull(checkNotNull(repository.skipOccurrence(saved.id, epochDay)).previous)
        assertNull(checkNotNull(repository.skipOccurrence(saved.id, epochDay)).previous)
    }

    @Test
    fun `pular rotina que nao existe mais nao grava nada e devolve null`() = runTest {
        val epochDay = LocalDate.now().toEpochDay()

        assertNull(repository.skipOccurrence(999, epochDay))

        assertNull(repository.occurrenceOverride(999, epochDay))
        assertTrue(db.routineOverrideDao().all().isEmpty())
    }

    @Test
    fun `restaurar so age se o dia ainda esta pulado`() = runTest {
        val saved = repository.add(routine())
        val epochDay = LocalDate.now().toEpochDay()
        val previous = checkNotNull(repository.skipOccurrence(saved.id, epochDay)).previous
        // Outra alteração no meio (ex.: remarcou pela tela de exceção): ela vence.
        repository.rescheduleOccurrence(saved.id, epochDay, newStartMinute = 1_000, newEpochDay = null)

        repository.restoreOccurrenceException(saved.id, epochDay, previous)

        assertEquals(1_000, repository.occurrenceOverride(saved.id, epochDay)?.newStartMinute)
    }
}
