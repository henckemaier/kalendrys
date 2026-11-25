package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineChange
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import java.time.LocalDate
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
 * Salvar na hora da rotina (v1.9.0, ADR-0002 15.2): [RoutineRepository.edit] aplica cada mudança
 * sobre a linha MAIS RECENTE e grava só as colunas dela; pausar e arquivar gravam só a própria
 * coluna. Alarme refeito só pelas mudanças de agenda.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineRepositoryEditTest {

    private lateinit var db: AppDatabase
    private lateinit var alarms: FakeRoutineAlarms
    private lateinit var notifications: FakeRoutineNotifications
    private lateinit var repository: RoutineRepository
    private lateinit var shopping: ShoppingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
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
            clock = SystemAppClock,
        )
        shopping = ShoppingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun row(id: Long) = db.routineDao().byId(id)!!

    private suspend fun saved(title: String = "Treino") =
        repository.add(Routine(title = title, startMinute = 600, durationMinutes = 30, startEpochDay = 100, endEpochDay = 200))

    @Test
    fun `mudancas que nao sao de agenda nao reagendam`() = runTest {
        val routine = saved()
        val listId = shopping.createList("Mercado")
        alarms.reset()

        repository.edit(routine.id, RoutineChange.Title("Corrida"))
        repository.edit(routine.id, RoutineChange.Notes("n"))
        repository.edit(routine.id, RoutineChange.Duration(45))
        repository.edit(routine.id, RoutineChange.Color(RoutineColor.entries.last()))
        repository.edit(routine.id, RoutineChange.Icon(RoutineIcon.entries.last()))
        repository.edit(routine.id, RoutineChange.Category(null))
        repository.edit(routine.id, RoutineChange.ShoppingList(listId))
        repository.edit(routine.id, RoutineChange.NewShoppingList("Farmácia"))

        assertTrue(alarms.calls.isEmpty())
        val after = row(routine.id)
        assertEquals("Corrida", after.title)
        assertEquals(45, after.durationMinutes)
    }

    @Test
    fun `mudancas de agenda reagendam`() = runTest {
        val routine = saved()
        alarms.reset()

        repository.edit(routine.id, RoutineChange.Start(700))
        repository.edit(routine.id, RoutineChange.Days(0b1))
        repository.edit(routine.id, RoutineChange.WeekInterval(2))
        repository.edit(routine.id, RoutineChange.ValidFrom(120))
        repository.edit(routine.id, RoutineChange.ValidUntil(180))
        repository.edit(routine.id, RoutineChange.Remind(false))

        assertEquals(6, alarms.calls.size)
        val after = row(routine.id)
        assertEquals(700, after.startMinute)
        assertEquals(0b1, after.daysOfWeek)
        assertEquals(2, after.weekInterval)
        assertEquals(120L, after.startEpochDay)
        assertEquals(180L, after.endEpochDay)
        assertFalse(after.remind)
        // A vigência do teste (dias 120 a 180 de 1970) já passou: nenhum dos dois alarmes.
        assertEquals(FakeRoutineAlarms.Call(remindAt = null, refreshAt = null), alarms.calls.last())
        // Sem vigência, o agendamento já vê a linha nova: sem aviso (Remind(false)), só o alarme
        // silencioso dos widgets, às 11:40.
        repository.edit(routine.id, RoutineChange.ValidFrom(null))
        repository.edit(routine.id, RoutineChange.ValidUntil(null))
        val last = alarms.calls.last()
        assertNull(last.remindAt)
        assertEquals(700, last.refreshAt!!.let { it.hour * 60 + it.minute })
    }

    @Test
    fun `titulo vazio e dia nenhum nao gravam`() = runTest {
        val routine = saved()

        assertFalse(repository.edit(routine.id, RoutineChange.Title(" ")))
        assertFalse(repository.edit(routine.id, RoutineChange.Days(0)))

        assertEquals(routine, row(routine.id))
    }

    @Test
    fun `inicio da vigencia depois do fim limpa o fim`() = runTest {
        val routine = saved()

        assertTrue(repository.edit(routine.id, RoutineChange.ValidFrom(250)))

        val after = row(routine.id)
        assertEquals(250L, after.startEpochDay)
        assertNull(after.endEpochDay)
    }

    @Test
    fun `fim da vigencia antes do inicio nao grava`() = runTest {
        val routine = saved()

        assertFalse(repository.edit(routine.id, RoutineChange.ValidUntil(50)))

        assertEquals(200L, row(routine.id).endEpochDay)
    }

    @Test
    fun `rotina que nao existe mais - nada acontece nem no alarme de quem nao e de agenda`() = runTest {
        alarms.reset()

        assertFalse(repository.edit(999, RoutineChange.Title("x")))
        assertFalse(repository.edit(999, RoutineChange.Color(RoutineColor.DEFAULT)))

        assertTrue(alarms.calls.isEmpty())
        // Mudança de agenda recusada ainda passa pelo change{} (catch-up antes da leitura, N2): só não grava.
        assertFalse(repository.edit(999, RoutineChange.Start(60)))
    }

    // --- Alterada por fora: nenhum dos dois lados se perde -----------------------------------------

    @Test
    fun `pausada por fora - editar o titulo mantem a pausa`() = runTest {
        val routine = saved()
        repository.pause(routine, now = 5_000L)

        repository.edit(routine.id, RoutineChange.Title("Corrida"))
        repository.edit(routine.id, RoutineChange.Start(700))

        val after = row(routine.id)
        assertEquals(5_000L, after.pausedAt)
        assertEquals("Corrida", after.title)
        assertEquals(700, after.startMinute)
    }

    @Test
    fun `pausar com objeto antigo depois de editar nao desfaz a edicao`() = runTest {
        val stale = saved()
        repository.edit(stale.id, RoutineChange.Title("Corrida"))
        repository.edit(stale.id, RoutineChange.Days(0b11))

        repository.pause(stale, now = 7_000L)
        repository.resume(stale)
        repository.archive(stale, now = 8_000L)

        val after = row(stale.id)
        assertEquals("Corrida", after.title)
        assertEquals(0b11, after.daysOfWeek)
        assertNull(after.pausedAt)
        assertEquals(8_000L, after.archivedAt)
    }

    @Test
    fun `arquivada por fora - editar nao desarquiva`() = runTest {
        val routine = saved()
        repository.archive(routine, now = 9_000L)

        repository.edit(routine.id, RoutineChange.Title("Corrida"))

        assertEquals(9_000L, row(routine.id).archivedAt)
    }

    @Test
    fun `concluida por fora - editar mantem a conclusao`() = runTest {
        val routine = saved()
        val today = LocalDate.now()
        repository.complete(routine.id, today)

        repository.edit(routine.id, RoutineChange.Title("Corrida"))

        assertTrue(routine.id in db.routineCompletionDao().doneOn(today.toEpochDay()))
    }

    @Test
    fun `lista excluida por fora - editar nao devolve o vinculo e vincular a ela nao grava`() = runTest {
        val listId = shopping.createList("Mercado")
        val routine = repository.add(Routine(title = "Feira", startMinute = 480, shoppingListId = listId))
        shopping.deleteList(listId)

        repository.edit(routine.id, RoutineChange.Title("Feira nova"))
        assertNull(row(routine.id).shoppingListId)

        assertFalse(repository.edit(routine.id, RoutineChange.ShoppingList(listId)))
        assertNull(row(routine.id).shoppingListId)
    }

    @Test
    fun `lista nova cria e vincula numa vez`() = runTest {
        val routine = saved()

        assertTrue(repository.edit(routine.id, RoutineChange.NewShoppingList(" Feira ")))

        val list = db.shoppingListDao().all().single()
        assertEquals("Feira", list.name)
        assertEquals(list.id, row(routine.id).shoppingListId)
    }

    @Test
    fun `criar rotina com lista nova grava as duas juntas`() = runTest {
        val routine = repository.add(Routine(title = "Feira", startMinute = 480), newListName = " Hortifruti ")

        val list = db.shoppingListDao().all().single()
        assertEquals("Hortifruti", list.name)
        assertEquals(list.id, routine.shoppingListId)
        assertNotNull(db.routineDao().byId(routine.id))
    }

    @Test
    fun `criar rotina com nome de lista em branco nao cria lista`() = runTest {
        val routine = repository.add(Routine(title = "Feira", startMinute = 480), newListName = "  ")

        assertTrue(db.shoppingListDao().all().isEmpty())
        assertNull(routine.shoppingListId)
    }
}
