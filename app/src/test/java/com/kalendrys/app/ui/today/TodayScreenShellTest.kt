package com.kalendrys.app.ui.today

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Botão "Hoje" da tela Hoje (ex-aba Rotinas) (O-1 da E3, correção do B-1): escrito na barra (TopBarAction
 * prominent), só fora do dia de hoje, e volta para ele. O Calendário já tinha esse teste; Rotinas não.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayScreenShellTest {

    @get:Rule
    val compose = createComposeRule()

    private val todayButton = hasText("Hoje") and hasClickAction()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TodayViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        viewModel = testDb.track(TodayRepositories(context, db).viewModel(DayOfWeek.SUNDAY))
    }

    @After
    fun tearDown() = testDb.close()

    private fun show(requestedDate: LocalDate?) {
        compose.setContent {
            KalendrysTheme {
                TodayScreen(
                    onOpenRoutine = {},
                    onNewRoutine = {},

                    onOpenAllRoutines = {},
                    onOpenSettings = {},
                    onOpenTask = {},
                    onOpenException = { _, _ -> },
                    requestedDate = requestedDate,
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Configurações").fetchSemanticsNodes().isNotEmpty() &&
                viewModel.uiState.value.isLoaded
        }
    }

    private fun waitForToday(present: Boolean) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(todayButton).fetchSemanticsNodes().isNotEmpty() == present
    }

    @Test
    fun `no dia de hoje nao ha Hoje na barra`() {
        show(requestedDate = null)

        compose.onAllNodes(todayButton).assertCountEquals(0)
    }

    @Test
    fun `fora do dia de hoje aparece Hoje escrito, que volta para ele e some`() {
        show(requestedDate = LocalDate.now().plusDays(3))

        waitForToday(present = true)
        compose.onNode(todayButton).assertIsDisplayed().performClick()

        waitForToday(present = false)
        assertEquals(true, viewModel.uiState.value.isShowingToday)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
