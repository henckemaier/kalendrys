package com.kalendrys.app.ui.today

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.domain.DayTimeline
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineOverrideType
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.domain.TaskSchedule
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tela Hoje (ex-aba Rotinas): progresso 3/8 da lista no bloco da trilha (ADR-0001, P8) e o NB-1. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayViewModelTest {

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var db: AppDatabase
    private lateinit var routineRepository: RoutineRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var shoppingRepository: ShoppingRepository

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        db = testDb.db
        routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
        )
        taskRepository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
        shoppingRepository = ShoppingRepository(db)
    }

    @After
    fun tearDown() {
        testDb.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = testDb.track(
        TodayViewModel(
            routineRepository,
            taskRepository,
            DayOfWeek.MONDAY,
            shoppingRepository,
            NewTaskDraftStore(),
            TodayRepository(taskRepository, routineRepository, shoppingRepository),
        ),
    )

    private suspend fun ReceiveTurbine<TodayViewModel.UiState>.awaitState(
        predicate: (TodayViewModel.UiState) -> Boolean,
    ): TodayViewModel.UiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    private fun TodayViewModel.UiState.block(title: String): DayTimeline.Block =
        timeline.filterIsInstance<DayTimeline.Block>().single { it.routine.title == title }

    @Test
    fun `bloco com lista mostra o progresso e acompanha as marcacoes`() = runTest {
        val listId = shoppingRepository.createList("Feira")
        shoppingRepository.add(listId, "Arroz", quantity = 1, priceCents = null)
        shoppingRepository.add(listId, "Feijão", quantity = 1, priceCents = null)
        routineRepository.add(Routine(title = "Feira", startMinute = 600, shoppingListId = listId))

        viewModel().uiState.test {
            var state = awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            assertEquals(ShoppingProgress(0, 2), state.progressFor(state.block("Feira").routine.shoppingListId))

            val arroz = shoppingRepository.observeItems(listId).first().first { it.name == "Arroz" }
            shoppingRepository.setPicked(arroz, picked = true)
            state = awaitState { it.progressFor(listId) == ShoppingProgress(1, 2) }
            assertEquals(ShoppingProgress(1, 2), state.progressFor(state.block("Feira").routine.shoppingListId))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bloco sem lista, com lista vazia ou vinculo orfao nao mostra progresso`() = runTest {
        val empty = shoppingRepository.createList("Vazia")
        routineRepository.add(Routine(title = "Sem lista", startMinute = 480))
        routineRepository.add(Routine(title = "Lista vazia", startMinute = 540, shoppingListId = empty))
        routineRepository.add(Routine(title = "Órfã", startMinute = 600, shoppingListId = 999))

        viewModel().uiState.test {
            val state = awaitState { it.isLoaded && it.timeline.filterIsInstance<DayTimeline.Block>().size == 3 }
            assertNull(state.progressFor(state.block("Sem lista").routine.shoppingListId))
            assertNull(state.progressFor(state.block("Lista vazia").routine.shoppingListId))
            assertNull(state.progressFor(state.block("Órfã").routine.shoppingListId))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- NB-1 (v1.8.0): desmarcar na trilha desfaz a conclusão MAIS RECENTE do dia ---

    /**
     * (1) única para amanhã concluída hoje (A); (2) data mudada para +3, o que a reabre; (3)
     * concluída de novo hoje (B); (4) desmarcada na trilha de hoje. Devolve (id da tarefa, A, B).
     */
    private fun twoEarlyCompletionsThenUncheck(aAt: Long, bAt: Long): Triple<Long, Long, Long> = runBlocking {
        val today = LocalDate.now()
        val timing = TaskTiming(date = today.plusDays(1), time = LocalTime.of(9, 0))
        val id = taskRepository.addTask("Levar o carro", timing = timing).id
        val a = taskRepository.logCompletion(id, completedAt = aAt)
        assertEquals(today.toEpochDay(), db.taskDao().byId(id)!!.dueDate)
        taskRepository.updateTask(db.taskDao().byId(id)!!.copy(dueDate = today.plusDays(3).toEpochDay()))
        val b = taskRepository.logCompletion(id, completedAt = bAt)
        assertEquals("B é outro registro (a tarefa estava reaberta)", true, b != a)
        assertEquals(today.toEpochDay(), db.taskDao().byId(id)!!.dueDate)

        val viewModel = viewModel()
        viewModel.uiState.test {
            // Espera a tarefa entre as Concluídas de hoje (as duas conclusões já carregadas).
            val state = awaitState { s -> s.completedTasks.any { it.task.id == id && it.done } }
            viewModel.toggleTask(state.completedTasks.single { it.task.id == id }.task, done = true)
            // Desfeita a B, a tarefa volta para +3 e não é pendente de hoje. Ela continua em Concluídas pelo
            // registro A, que também é de hoje (E3: Concluídas vai pela data do REGISTRO).
            awaitState { s ->
                s.dayTasks.none { it.task.id == id } &&
                    s.completedTasks.any { it.task.id == id && it.task.dueDate == today.plusDays(3).toEpochDay() }
            }
            cancelAndIgnoreRemainingEvents()
        }
        Triple(id, a, b)
    }

    private fun assertBackToPlusThreeWithOnlyA(id: Long, a: Long) = runBlocking {
        val today = LocalDate.now()
        val task = db.taskDao().byId(id)!!
        assertEquals("a data volta para +3", today.plusDays(3).toEpochDay(), task.dueDate)
        assertEquals("sobra só o A", listOf(a), db.completionLogDao().observeForTask(id).first().map { it.id })
        val last = db.completionLogDao().lastCompletion(id)
        assertEquals("pendente na data certa", false, TaskSchedule.isFinished(task, last, ZoneId.systemDefault()))
        assertEquals(
            TaskSchedule.Status.UPCOMING,
            TaskSchedule.status(task, today, doneEver = TaskSchedule.isFinished(task, last, ZoneId.systemDefault()), doneToday = false),
        )
    }

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `duas conclusoes adiantadas no dia - desmarcar na trilha desfaz a mais recente`() {
        val (id, a, _) = twoEarlyCompletionsThenUncheck(aAt = startOfToday() + 1_000, bAt = startOfToday() + 2_000)

        assertBackToPlusThreeWithOnlyA(id, a)
    }

    @Test
    fun `duas conclusoes no mesmo horario - desmarcar desfaz a de maior id`() {
        val at = startOfToday() + 1_000
        val (id, a, b) = twoEarlyCompletionsThenUncheck(aAt = at, bAt = at)

        assertEquals(true, b > a)
        assertBackToPlusThreeWithOnlyA(id, a)
    }

    // --- P5 (v1.8.0, E3): Pular com Desfazer ---

    @Test
    fun `pular mantem o bloco na trilha marcado, avisa para Desfazer e desfazer o devolve`() = runTest {
        val today = LocalDate.now()
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            vm.skip(treino.id, today)
            val skipped = awaitState { it.block("Treino").skipped }
            assertEquals(false, skipped.block("Treino").isActive)
            assertEquals(TodayViewModel.Skipped(treino.id, today, previous = null), vm.skipped.first())
            assertEquals(RoutineOverrideType.SKIP, routineRepository.occurrenceOverride(treino.id, today.toEpochDay())?.type)

            vm.unskip(treino.id, today)
            val back = awaitState { !it.block("Treino").skipped }
            assertEquals(true, back.block("Treino").isActive)
            assertNull(routineRepository.occurrenceOverride(treino.id, today.toEpochDay()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dia remarcado, pular e Desfazer na mensagem - a remarcacao volta`() = runTest {
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        routineRepository.rescheduleOccurrence(treino.id, epochDay, newStartMinute = 930, newEpochDay = null)
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block && item.startMinute == 930 } }
            vm.skip(treino.id, today)
            awaitState { it.block("Treino").skipped }
            val event = vm.skipped.first()
            assertEquals(RoutineOverrideType.RESCHEDULE, event.previous?.type)
            assertEquals(930, event.previous?.newStartMinute)

            vm.undoSkip(event)
            val back = awaitState { !it.block("Treino").skipped }
            assertEquals(930, back.block("Treino").startMinute)
            val restored = routineRepository.occurrenceOverride(treino.id, epochDay)
            assertEquals(RoutineOverrideType.RESCHEDULE, restored?.type)
            assertEquals(930, restored?.newStartMinute)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Desfazer pular do bloco em dia remarcado e pulado so tira o pular`() = runTest {
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        routineRepository.rescheduleOccurrence(treino.id, epochDay, newStartMinute = 930, newEpochDay = null)
        routineRepository.skipOccurrence(treino.id, epochDay)
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block && item.skipped } }
            vm.unskip(treino.id, today)
            val back = awaitState { !it.block("Treino").skipped }
            // Sem exceção nenhuma: horário normal (a remarcação não volta).
            assertEquals(600, back.block("Treino").startMinute)
            assertNull(routineRepository.occurrenceOverride(treino.id, epochDay))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pular sem excecao no dia - o evento nao leva anterior e Desfazer na mensagem so tira o pular`() = runTest {
        val today = LocalDate.now()
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        val vm = viewModel()

        vm.uiState.test {
            awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            vm.skip(treino.id, today)
            awaitState { it.block("Treino").skipped }
            val event = vm.skipped.first()
            assertNull(event.previous)

            vm.undoSkip(event)
            awaitState { !it.block("Treino").skipped }
            assertNull(routineRepository.occurrenceOverride(treino.id, today.toEpochDay()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bloco pulado nao se conclui pelo circulo`() = runTest {
        val today = LocalDate.now()
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        routineRepository.skipOccurrence(treino.id, today.toEpochDay())
        val vm = viewModel()

        vm.uiState.test {
            val state = awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            vm.toggle(state.block("Treino"))
            // As alterações do repositório passam uma por vez: depois desta, um concluir pedido acima já teria gravado.
            routineRepository.add(Routine(title = "Depois", startMinute = 700))
            // O toggle ignora o pulado: nenhuma conclusão gravada.
            assertEquals(false, treino.id in db.routineCompletionDao().doneOn(today.toEpochDay()))
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- A1 do Esquadro na E3: bloco remarcado vindo de outro dia age no dia de ORIGEM ---

    @Test
    fun `pular bloco remarcado de ontem troca a remarcacao por pular na origem e Desfazer a devolve`() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        // Só no dia da semana de ontem; a de ontem foi remarcada para hoje às 15:30.
        val treino = routineRepository.add(
            Routine(title = "Treino", startMinute = 600, createdAt = 0, daysOfWeek = RoutineDays.bit(yesterday.dayOfWeek)),
        )
        routineRepository.rescheduleOccurrence(treino.id, yesterday.toEpochDay(), newStartMinute = 930, newEpochDay = today.toEpochDay())
        val vm = viewModel()

        vm.uiState.test {
            val shown = awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }.block("Treino")
            assertEquals(yesterday, shown.occurrenceDate)
            assertEquals(930, shown.startMinute)

            // A tela passa o dia da ocorrência (block.occurrenceDate), não o mostrado.
            vm.skip(treino.id, shown.occurrenceDate)
            awaitState { state -> state.timeline.none { it is DayTimeline.Block && it.routine.id == treino.id } }
            val event = vm.skipped.first()
            assertEquals(yesterday, event.date)
            assertEquals(RoutineOverrideType.RESCHEDULE, event.previous?.type)
            assertEquals(RoutineOverrideType.SKIP, routineRepository.occurrenceOverride(treino.id, yesterday.toEpochDay())?.type)
            assertNull("nenhum pular inútil no dia mostrado", routineRepository.occurrenceOverride(treino.id, today.toEpochDay()))

            vm.undoSkip(event)
            val back = awaitState { state -> state.timeline.any { it is DayTimeline.Block && it.routine.id == treino.id } }
            assertEquals(930, back.block("Treino").startMinute)
            assertEquals(yesterday, back.block("Treino").occurrenceDate)
            assertEquals(RoutineOverrideType.RESCHEDULE, routineRepository.occurrenceOverride(treino.id, yesterday.toEpochDay())?.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bloco do proprio dia segue com o dia mostrado`() = runTest {
        val treino = routineRepository.add(Routine(title = "Treino", startMinute = 600))
        val vm = viewModel()

        vm.uiState.test {
            val state = awaitState { it.isLoaded && it.timeline.any { item -> item is DayTimeline.Block } }
            assertEquals(LocalDate.now(), state.block("Treino").occurrenceDate)
            assertEquals(treino.id, state.block("Treino").routine.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Concluir num dia passado grava NO PRÓPRIO DIA (decisão do Esquadro, v1.8.0) ---

    @Test
    fun `repetida concluida pela linha de ontem fica feita ontem e hoje continua pendente`() = runTest {
        val yesterday = LocalDate.now().minusDays(1)
        val id = taskRepository.addTask("Remédio", timing = TaskTiming(date = yesterday, time = LocalTime.of(10, 0), repeat = ReminderRepeat.DAILY)).id
        val viewModel = viewModel()
        viewModel.select(yesterday)
        viewModel.uiState.test {
            awaitState { s -> s.selectedDate == yesterday }
            viewModel.toggleTask(db.taskDao().byId(id)!!, done = false)
            awaitState { s -> s.selectedDate == yesterday }
            cancelAndIgnoreRemainingEvents()
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (db.completionLogDao().all().none { it.taskId == id }) kotlinx.coroutines.delay(20) }
        }
        val log = db.completionLogDao().all().single { it.taskId == id }
        assertEquals(yesterday, Instant.ofEpochMilli(log.completedAt).atZone(ZoneId.systemDefault()).toLocalDate())
        // Números de hoje (o retrato do widget): a tarefa de hoje continua pendente.
        val overview = com.kalendrys.app.data.repository.TodayRepository(taskRepository, routineRepository, shoppingRepository).current()
        assertEquals(true, overview.pending.any { it.taskId == id })
        assertEquals(0, overview.doneCount)
    }
}
