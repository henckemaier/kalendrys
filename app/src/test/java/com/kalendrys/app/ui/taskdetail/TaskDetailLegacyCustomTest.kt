package com.kalendrys.app.ui.taskdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ReminderRepeat
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * NB-E1-A do Lince, reescrito para o detalhe que grava na hora (decisão do Esquadro na E2): uma
 * repetição personalizada antiga sem dia utilizável (nunca ocorre) é lida como "não repete"
 * ([TaskTiming.of]), mas só é REGRAVADA se a pessoa mexer em data, hora ou repetição.
 *
 * - Abrir o detalhe não marca nada como mexido e não grava nada.
 * - Mudar só o título não altera repetição, data nem lembrete.
 * - Uma mudança de data, hora ou repetição regrava a repetição (com o que a tela mostra).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskDetailLegacyCustomTest {

    private val scheduler = TestCoroutineScheduler()
    private val zone: ZoneId = ZoneId.systemDefault()
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: TaskRepository
    private val viewModels = mutableListOf<TaskDetailViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { withTimeoutOrNull(TIMEOUT_MS) { it.awaitPendingWrites() } } }
        // B2: o fim do ViewModel (o que o onCleared faz), na thread do teste, antes de fechar o banco.
        viewModels.forEach { it.finish() }
        testDb.close()
        Dispatchers.resetMain()
    }

    /** A personalizada quebrada, como uma versão antiga a deixava no banco. */
    private fun insertBroken(): Task = runBlocking {
        val broken = TaskTiming(LocalDate.now().plusDays(4), LocalTime.of(9, 0)).applyTo(Task(title = "Regar as plantas", createdAt = TEST_CREATED_AT), zone)
            .copy(repeat = ReminderRepeat.CUSTOM, byWeekdays = 0, intervalUnit = null)
        broken.copy(id = testDb.db.taskDao().insert(broken))
    }

    private fun viewModel(id: Long) = testDb.track(
        TaskDetailViewModel(
            SavedStateHandle(mapOf(TASK_ID_ARG to id)),
            repository,
            ShoppingRepository(testDb.db, clock = SystemAppClock),
            testUndoOffers(testDb, tasks = repository),
            testDb.appScope(),
            clock = SystemAppClock,
        ),
    ).also { viewModels += it }

    private suspend fun TaskDetailViewModel.awaitUi(predicate: (TaskDetailViewModel.UiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { uiState.first(predicate) } }

    private suspend fun stored(id: Long) = repository.observeTask(id).filterNotNull().first()

    @Test
    fun `personalizada quebrada - abrir o detalhe nao marca nada como mexido nem grava`() = runBlocking {
        val broken = insertBroken()
        val vm = viewModel(broken.id)

        val state = vm.awaitUi { it.task != null }
        assertEquals("lida como não repete", ReminderRepeat.NONE, state.timing?.repeat)
        assertFalse("abrir conta como alteração", state.canRevert)
        assertFalse("lembrete da abertura conta como novo", state.reminderChanged)
        vm.leave()
        vm.awaitPendingWrites()
        assertEquals(broken, stored(broken.id))
    }

    @Test
    fun `personalizada quebrada - mudar so o titulo mantem repeticao, data e lembrete gravados`() = runBlocking {
        val broken = insertBroken()
        val vm = viewModel(broken.id)
        vm.awaitUi { it.task != null }

        vm.updateTitle("Regar as plantas da sala")
        vm.flush()
        vm.awaitPendingWrites()

        val after = stored(broken.id)
        assertEquals("Regar as plantas da sala", after.title)
        assertEquals(broken.copy(title = after.title), after)
    }

    @Test
    fun `personalizada quebrada - mudar a hora regrava a repeticao como a tela mostra`() = runBlocking {
        val broken = insertBroken()
        val vm = viewModel(broken.id)
        val timing = vm.awaitUi { it.timing != null }.timing!!

        vm.updateTiming(timing.copy(time = LocalTime.of(10, 0)))
        vm.awaitPendingWrites()

        val after = stored(broken.id)
        assertEquals(ReminderRepeat.NONE, after.repeat)
        assertEquals(timing.copy(time = LocalTime.of(10, 0)).reminderAt(zone), after.reminderAt)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
