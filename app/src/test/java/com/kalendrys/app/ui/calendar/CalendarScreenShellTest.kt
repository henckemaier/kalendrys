package com.kalendrys.app.ui.calendar

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
 * Casca da aba Calendário (ADR-0002, 12.2; E3): barra pequena com "Hoje" e Configurações como
 * ícones e o FAB de nova tarefa. O comportamento é o de antes: "Hoje" só fora do dia de hoje e
 * volta para ele; o FAB abre o painel de nova tarefa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CalendarScreenShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: CalendarViewModel
    private var settingsOpened = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        viewModel = testDb.track(
            CalendarViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao()),
                RoutineRepository(
                    db.routineDao(),
                    db.routineCompletionDao(),
                    db.routineOverrideDao(),
                    FakeRoutineAlarms(context),
                    FakeRoutineNotifications(context),
                    db.shoppingItemDao(),
                    InMemoryRoutineCatchUpMark(),
                ),
                ShoppingRepository(db),
            ),
        )
        compose.setContent {
            KalendrysTheme {
                CalendarScreen(
                    onOpenTask = {},
                    onOpenRoutine = {},
                    onOpenSettings = { settingsOpened++ },
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
        compose.onAllNodesWithContentDescription("Hoje").assertCountEquals(0)

        compose.onNodeWithContentDescription("Configurações").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `fora do dia de hoje aparece Hoje, que volta para ele e some`() {
        compose.onNodeWithContentDescription("Próximo mês").performClick()
        waitForToday(present = true)
        compose.onNodeWithContentDescription("Hoje").assertIsDisplayed().performClick()

        waitForToday(present = false)
        compose.onAllNodesWithContentDescription("Hoje").assertCountEquals(0)
        assertEquals(true, viewModel.uiState.value.isShowingToday)
    }

    /** O estado passa pelos flows do ViewModel (com o Room no meio): espera a barra refletir. */
    private fun waitForToday(present: Boolean) = compose.waitUntil(LOAD_TIMEOUT_MS) {
        compose.onAllNodesWithContentDescription("Hoje").fetchSemanticsNodes().isNotEmpty() == present
    }

    @Test
    fun `FAB de nova tarefa abre o painel`() {
        compose.onAllNodesWithText("Título").assertCountEquals(0)

        compose.onNodeWithContentDescription("Nova tarefa").performClick()

        compose.onNodeWithText("Título").assertIsDisplayed()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
    }
}
