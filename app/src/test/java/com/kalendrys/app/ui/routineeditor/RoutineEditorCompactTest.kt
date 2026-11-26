package com.kalendrys.app.ui.routineeditor

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.SavedExerciseRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * E3-B da Coruja: criação de rotina em altura COMPACTA (paisagem de telefone, abaixo do corte que
 * recolhe o FAB e o painel). O "Criar rotina" fica na barra do topo, como ação em destaque, e o rodapé
 * some; ligado só com título, como no rodapé da altura normal.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class RoutineEditorCompactTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        val routines = testRoutineRepository(db)
        val vm = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to NEW_ROUTINE_ID)),
                routines,
                ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock),
                SavedExerciseRepository(db.savedExerciseDao(), clock = SystemAppClock),
                testUndoOffers(testDb, routines),
                testDb.appScope(),
                clock = SystemAppClock,
            ),
        )
        compose.setContent { KalendrysTheme { RoutineEditorScreen(onBack = {}, viewModel = vm) } }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Criar rotina").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    @Test
    fun `altura compacta - Criar rotina na barra do topo, sem rodape`() {
        val nodes = compose.onAllNodes(hasText("Criar rotina") and hasClickAction()).fetchSemanticsNodes()
        assertEquals("um Criar rotina só (sem rodapé)", 1, nodes.size)
        // Na faixa da barra do topo (64dp), não no fim da tela.
        val top = nodes.single().boundsInRoot.top
        assertTrue("Criar rotina em $top, fora da barra do topo", top < 80 * compose.density.density)
    }

    @Test
    fun `altura compacta - Criar rotina liga com o titulo`() {
        val create = compose.onNode(hasText("Criar rotina") and hasClickAction())
        create.assertIsNotEnabled()

        compose.onNode(hasSetTextAction() and hasText("Título")).performTextReplacement("Corrida")

        create.assertIsEnabled()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
