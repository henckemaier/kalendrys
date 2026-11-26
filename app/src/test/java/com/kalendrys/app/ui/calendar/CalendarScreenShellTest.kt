package com.kalendrys.app.ui.calendar

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Casca da aba Calendário (ADR-0002, 12.2; E3): barra pequena com "Hoje" escrito (O-1) e
 * Configurações como ícone, e o FAB de nova tarefa. O comportamento é o de antes: "Hoje" só fora
 * do dia de hoje e volta para ele; o FAB abre o painel de nova tarefa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CalendarScreenShellTest {

    /** "Hoje" é botão de texto na barra (TopBarAction prominent, O-1 da E3). */
    private val todayButton = hasText("Hoje") and hasClickAction()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: CalendarViewModel
    private var settingsOpened = 0
    private val newTaskRequests = mutableListOf<java.time.LocalDate>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        viewModel = testDb.track(
            CalendarViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), clock = SystemAppClock),
                RoutineRepository(
                    db.routineDao(),
                    db.routineCompletionDao(),
                    db.routineOverrideDao(),
                    FakeRoutineAlarms(context),
                    FakeRoutineNotifications(context),
                    InMemoryRoutineCatchUpMark(),
                    clock = SystemAppClock,
                ),
                ShoppingRepository(db, clock = SystemAppClock),
                clock = SystemAppClock,
            ),
        )
        compose.setContent {
            KalendrysTheme {
                CalendarScreen(
                    onOpenTask = {},
                    onOpenRoutine = {},
                    onOpenSettings = { settingsOpened++ },
                    onNewTask = { newTaskRequests += it },
                    viewModel = viewModel,
                )
            }
        }
        // O Room responde no executor dele, fora do Compose: espera a tela carregar. O cartão do mês
        // (e o "Próximo mês" nele) só existe carregado; a lista do dia fica abaixo da dobra.
        compose.waitUntil(LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Próximo mês").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `barra pequena com titulo e Configuracoes, sem Hoje no dia de hoje`() {
        compose.onNodeWithText("Calendário").assertIsDisplayed()
        compose.onAllNodes(todayButton).assertCountEquals(0)

        compose.onNodeWithContentDescription("Configurações").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `fora do dia de hoje aparece Hoje, que volta para ele e some`() {
        compose.onNodeWithContentDescription("Próximo mês").performClick()
        waitForToday(present = true)
        compose.onNode(todayButton).assertIsDisplayed().performClick()

        waitForToday(present = false)
        compose.onAllNodes(todayButton).assertCountEquals(0)
        assertEquals(true, viewModel.uiState.value.isShowingToday)
    }

    /** O estado passa pelos flows do ViewModel (com o Room no meio): espera a barra refletir. */
    private fun waitForToday(present: Boolean) = compose.waitUntil(LOAD_TIMEOUT_MS) {
        compose.onAllNodes(todayButton).fetchSemanticsNodes().isNotEmpty() == present
    }

    @Test
    fun `FAB de nova tarefa pede o painel com o dia selecionado`() {
        // O painel mora no topo da navegação (E6): a tela só pede, com o dia selecionado (hoje ao abrir).
        // A descrição traz o dia selecionado (new_task_on_day), como antes da casca.
        compose.onNodeWithContentDescription("Nova tarefa em ", substring = true).performClick()

        assertEquals(listOf(java.time.LocalDate.now()), newTaskRequests)
        compose.onAllNodesWithText("Título").assertCountEquals(0)
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
