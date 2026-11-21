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
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
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

    // --- Reinício da lista ao concluir o bloco (ADR-0001, P4) ---

    private suspend fun routineWithList(title: String = "Mercado", listId: Long? = null): Pair<Routine, Long> {
        val shopping = ShoppingRepository(db)
        val id = listId ?: shopping.createList(title).also { lid ->
            shopping.add(lid, "Arroz", 1, null)
            shopping.add(lid, "Feijão", 1, null)
            db.shoppingItemDao().byList(lid).forEach { shopping.setPicked(it, true) }
        }
        return repository.add(Routine(title = title, startMinute = 600, shoppingListId = id)) to id
    }

    private suspend fun picked(listId: Long) = db.shoppingItemDao().byList(listId).map { it.picked }

    @Test
    fun `concluir o bloco de hoje zera a lista e desfazer restaura`() = runTest {
        val today = LocalDate.now()
        val (routine, listId) = routineWithList()

        repository.complete(routine.id, today, today)
        assertEquals(listOf(false, false), picked(listId))

        repository.uncomplete(routine.id, today)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `concluir dia passado nao mexe na lista`() = runTest {
        val today = LocalDate.now()
        val (routine, listId) = routineWithList()

        repository.complete(routine.id, today.minusDays(1), today)
        assertEquals(listOf(true, true), picked(listId))
        repository.uncomplete(routine.id, today.minusDays(1))
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `concluir duas vezes nao perde as marcacoes guardadas`() = runTest {
        val today = LocalDate.now()
        val (routine, listId) = routineWithList()

        repository.complete(routine.id, today, today)
        repository.complete(routine.id, today, today)
        repository.uncomplete(routine.id, today)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `rotina sem lista ou com vinculo orfao conclui sem falhar`() = runTest {
        val today = LocalDate.now()
        val plain = repository.add(Routine(title = "Sem lista", startMinute = 600))
        val orphan = repository.add(Routine(title = "Órfã", startMinute = 600, shoppingListId = 999))

        repository.complete(plain.id, today, today)
        repository.complete(orphan.id, today, today)
        repository.uncomplete(orphan.id, today)
        assertEquals(listOf(plain.id), db.routineCompletionDao().doneOn(today.toEpochDay()))
    }

    @Test
    fun `concluir bloco de hoje de rotina arquivada nao mexe na lista`() = runTest {
        // P5: o vínculo de rotina arquivada é mantido no banco, mas ignorado no reinício.
        val today = LocalDate.now()
        val (routine, listId) = routineWithList()
        repository.archive(routine)

        repository.complete(routine.id, today, today)
        assertEquals(listOf(true, true), picked(listId))
        assertEquals(listId, db.routineDao().byId(routine.id)!!.shoppingListId)
    }

    @Test
    fun `lista compartilhada por duas rotinas zera para ambas`() = runTest {
        val today = LocalDate.now()
        val (first, listId) = routineWithList("Feira")
        val (second, _) = routineWithList("Mercado", listId)

        repository.complete(first.id, today, today)
        assertEquals(listOf(false, false), picked(listId))
        // O desfazer do outro dono não tem reinício para reverter.
        repository.uncomplete(second.id, today)
        assertEquals(listOf(false, false), picked(listId))
        repository.uncomplete(first.id, today)
        assertEquals(listOf(true, true), picked(listId))
    }

    @Test
    fun `concluir a rotina passa ao alarme as concluidas do dia do proximo inicio e desfazer tira`() = runTest {
        val saved = repository.add(routine(startMinute = 600).copy(remind = true))
        val day = alarms.calls.last().let { call ->
            com.kalendrys.app.domain.RoutineSchedule.nextStart(call.routines, call.after, java.time.ZoneId.systemDefault(), call.overridesByRoutine)!!.at.toLocalDate()
        }

        repository.complete(saved.id, day)
        assertEquals(setOf(saved.id), alarms.calls.last().doneOnStartDay)

        repository.uncomplete(saved.id, day)
        assertEquals(emptySet<Long>(), alarms.calls.last().doneOnStartDay)
    }

    // --- P5 (v1.8.0, E3): pular devolve a exceção anterior; o Desfazer da mensagem a recoloca ---

    @Test
    fun `pular devolve a excecao que substituiu e restaurar a recoloca, reagendando`() = runTest {
        val saved = repository.add(routine())
        val epochDay = LocalDate.now().toEpochDay()
        repository.rescheduleOccurrence(saved.id, epochDay, newStartMinute = 930, newEpochDay = null)

        val previous = repository.skipOccurrence(saved.id, epochDay)
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

        assertNull(repository.skipOccurrence(saved.id, epochDay))
        assertNull(repository.skipOccurrence(saved.id, epochDay))
    }

    @Test
    fun `restaurar so age se o dia ainda esta pulado`() = runTest {
        val saved = repository.add(routine())
        val epochDay = LocalDate.now().toEpochDay()
        val previous = repository.skipOccurrence(saved.id, epochDay)
        // Outra alteração no meio (ex.: remarcou pela tela de exceção): ela vence.
        repository.rescheduleOccurrence(saved.id, epochDay, newStartMinute = 1_000, newEpochDay = null)

        repository.restoreOccurrenceException(saved.id, epochDay, previous)

        assertEquals(1_000, repository.occurrenceOverride(saved.id, epochDay)?.newStartMinute)
    }
}
