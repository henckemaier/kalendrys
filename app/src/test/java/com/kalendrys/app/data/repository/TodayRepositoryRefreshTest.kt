package com.kalendrys.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-1 da v1.6.1: widget "Resumo do dia" com o "A seguir" parado num item que já começou. Numa sessão
 * aberta do Glance, `update()` só recompõe com o que o [TodayRepository.observe] já emitiu; se o
 * `delay` até o próximo horário acordar tarde (processo congelado, sono profundo, relógio ajustado),
 * o retrato fica velho. [TodayRepository.refresh], chamado por `KalendrysWidgets.updateAll`, recalcula.
 *
 * O fluxo roda em [Dispatchers.Default] para o `delay` ser de verdade (minutos): o tempo virtual do
 * runTest pularia a espera e esconderia o problema.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayRepositoryRefreshTest {

    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var today: TodayRepository

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2030, 1, 7)

    /** Relógio de parede visto pelo retrato; o teste o adianta sem o `delay` acordar. */
    @Volatile
    private var now: LocalDateTime = day.atTime(5, 39)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tasks = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        val routines = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            mark = InMemoryRoutineCatchUpMark(),
        )
        today = TodayRepository(
            tasks,
            routines,
            ShoppingRepository(db),
            dates = { flowOf(day) },
            zone = { zone },
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun task(title: String, hour: Int, minute: Int) {
        tasks.addTask(title, timing = TaskTiming(date = day, time = LocalTime.of(hour, minute)))
    }

    @Test
    fun `refresh tira do a seguir o item que ja comecou mesmo com o delay atrasado`() = runTest {
        task("P1", 5, 40)
        task("T2", 5, 45)
        today.observe().flowOn(Dispatchers.Default).test {
            assertEquals("P1", awaitItem().nextUp?.title)
            // 05:40 chegou no relógio de parede, mas o delay (monotônico) ainda não acordou: é a sessão
            // do widget recebendo o update() do alarme das 05:40.
            now = day.atTime(5, 40, 0, 200_000_000)
            today.refresh()
            assertEquals("T2", awaitItem().nextUp?.title)
            // O mesmo às 05:45, quando o lembrete toca: o "a seguir" sai de T2.
            now = day.atTime(5, 45, 0, 200_000_000)
            today.refresh()
            assertEquals(null, awaitItem().nextUp?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh sem mudanca de horario recalcula o mesmo retrato`() = runTest {
        task("T2", 5, 45)
        today.observe().flowOn(Dispatchers.Default).test {
            val first = awaitItem()
            today.refresh()
            assertEquals(first, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
