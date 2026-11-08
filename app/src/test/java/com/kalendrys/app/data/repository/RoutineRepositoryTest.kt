package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        repository = RoutineRepository(db.routineDao(), db.routineCompletionDao(), alarms, notifications)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun routine(title: String = "Treino", startMinute: Int = 600) =
        Routine(title = title, startMinute = startMinute)

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
}
