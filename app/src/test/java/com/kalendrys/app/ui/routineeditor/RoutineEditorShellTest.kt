package com.kalendrys.app.ui.routineeditor

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Editor de rotina na casca da v1.7.0 (ADR-0002, 12.2, E3): "Salvar" escrito na barra; Duplicar,
 * Pausar e Excluir no menu com rótulo; a lista recebe o preenchimento do AppScaffold e rola até o
 * último campo (Observações), que pega o foco.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutineEditorShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private var routineId = 0L
    private var closed = 0

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        val routineRepository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
        )
        val routine = runBlocking { routineRepository.add(Routine(title = "Treino", startMinute = 420)) }
        routineId = routine.id
        val viewModel = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to routine.id)),
                routineRepository,
                RoutineCategoryRepository(db),
                ShoppingRepository(db),
                ExerciseRepository(db.exerciseItemDao()),
            ),
        )
        compose.setContent {
            KalendrysTheme { RoutineEditorScreen(onBack = { closed++ }, viewModel = viewModel) }
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `Salvar escrito na barra e as acoes raras no menu com rotulo`() {
        compose.onNodeWithText("Editar rotina").assertIsDisplayed()
        compose.onNodeWithText("Salvar").assertIsDisplayed().assertIsNotEnabled()
        listOf("Duplicar rotina", "Pausar rotina", "Excluir rotina").forEach { label ->
            val shown = compose.onAllNodes(hasText(label) or hasContentDescription(label)).fetchSemanticsNodes()
            assertEquals(label, 0, shown.size)
        }

        compose.onNodeWithContentDescription("Mais opções").performClick()

        compose.onNodeWithText("Duplicar rotina").assertIsDisplayed()
        compose.onNodeWithText("Pausar rotina").assertIsDisplayed()
        compose.onNodeWithText("Excluir rotina").performClick()
        compose.onNodeWithText("Excluir rotina?").assertIsDisplayed()
    }

    /** "Salvar" é o TextButton da barra (TopBarAction.prominent): liga com a alteração, grava e fecha. */
    @Test
    fun `alterar liga o Salvar da barra e tocar nele grava e fecha o editor`() {
        compose.onNode(hasSetTextAction() and hasText("Treino")).performTextReplacement("Treino leve")
        compose.waitForIdle()

        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()

        // O save passa por várias consultas no executor do Room, e cada uma volta ao Main: o
        // waitUntil sozinho não drena o looper principal do Robolectric entre elas, então drenamos aqui.
        compose.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            closed == 1
        }
        assertEquals("Treino leve", runBlocking { testDb.db.routineDao().byId(routineId)?.title })
    }

    @Test
    fun `a lista rola ate o ultimo campo e ele pega o foco`() {
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("notes")

        val notes = compose.onNode(hasSetTextAction() and hasText("Observações (opcional)"))
        notes.assertIsDisplayed().performClick()
        notes.assertIsFocused()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
