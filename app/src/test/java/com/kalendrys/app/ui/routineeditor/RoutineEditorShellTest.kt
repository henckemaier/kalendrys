package com.kalendrys.app.ui.routineeditor

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
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
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testRoutineRepository
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Editor de rotina na casca da v1.9.0 (ADR-0002 15.5): sem Salvar; Pausar, Duplicar, Reverter e
 * Excluir no menu; seções na ordem da P4; "Mais opções" recolhido com Vigência e Observações (aberto
 * se já houver valor); na criação, "Criar rotina" no rodapé. Tela alta para todas as seções compostas.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-port")
class RoutineEditorShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var routines: RoutineRepository
    private lateinit var offers: UndoOffers
    private var closed = 0
    private var viewModel: RoutineEditorViewModel? = null

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        routines = testRoutineRepository(testDb.db)
        offers = testUndoOffers(testDb, routines)
    }

    @After
    fun tearDown() {
        runBlocking { withTimeoutOrNull(TIMEOUT_MS) { viewModel?.awaitPendingWrites() } }
        testDb.close()
    }

    private fun show(routineId: Long) {
        val db = testDb.db
        val vm = testDb.track(
            RoutineEditorViewModel(
                SavedStateHandle(mapOf(ROUTINE_ID_ARG to routineId)),
                routines,
                RoutineCategoryRepository(db),
                ExerciseRepository(db.exerciseItemDao(), clock = SystemAppClock),
                offers,
                testDb.appScope(),
                clock = SystemAppClock,
            ),
        )
        viewModel = vm
        compose.setContent { KalendrysTheme { RoutineEditorScreen(onBack = { closed++ }, viewModel = vm) } }
    }

    private fun showSaved(routine: Routine = Routine(title = "Treino", startMinute = 420, createdAt = TEST_CREATED_AT)): Long {
        val saved = runBlocking { routines.add(routine) }
        show(saved.id)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(saved.title).fetchSemanticsNodes().isNotEmpty() }
        return saved.id
    }

    /** O Room volta ao Main a cada consulta: drena o looper enquanto espera. */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
        condition()
    }

    @Test
    fun `edicao - sem Salvar, e as acoes no menu com rotulo`() {
        showSaved()
        compose.onNodeWithText("Editar rotina").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Salvar").fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("Mais opções").performClick()

        compose.onNodeWithText("Pausar rotina").assertIsDisplayed()
        compose.onNodeWithText("Duplicar rotina").assertIsDisplayed()
        // Nada mexido na sessão: Reverter desligado.
        compose.onNode(hasText("Reverter alterações") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Excluir rotina").assertIsDisplayed()
    }

    @Test
    fun `edicao - secoes na ordem da P4`() {
        showSaved()
        // Sem a seção Lista desde a v1.10.0 (ADR-0002 16.10.5): rotina não tem lista.
        assertEquals(0, compose.onAllNodes(hasText("Lista de compras"), useUnmergedTree = true).fetchSemanticsNodes().size)
        val order = listOf("Título", "Quando", "Avisar no início", "Aparência")
        val tops = order.map { label ->
            compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        }
        assertEquals("ordem $order: $tops", tops.sorted(), tops)
        // "Mais opções" (cabeçalho da lista, não o menu) vem depois da Aparência.
        val more = compose.onAllNodes(hasText("Mais opções"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(more > tops.last())
    }

    @Test
    fun `edicao - Mais opcoes recolhido esconde vigencia e observacoes e abre no toque`() {
        showSaved()
        assertEquals(0, compose.onAllNodesWithText("Vigência").fetchSemanticsNodes().size)

        compose.onNode(hasText("Mais opções") and hasClickAction() and isHeading).performClick()

        compose.onNodeWithText("Vigência").assertIsDisplayed()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("notes")
        val notes = compose.onNode(hasSetTextAction() and hasText("Observações (opcional)"))
        notes.assertIsDisplayed().performClick()
        notes.assertIsFocused()
    }

    @Test
    fun `edicao - rotina com vigencia abre Mais opcoes ja aberto`() {
        showSaved(Routine(title = "Temporada", startMinute = 420, endEpochDay = 20_500, createdAt = TEST_CREATED_AT))

        compose.onNodeWithText("Vigência").assertIsDisplayed()
    }

    @Test
    fun `edicao - mudar o titulo grava sem Salvar e liga o Reverter`() {
        val id = showSaved()

        compose.onNode(hasSetTextAction() and hasText("Treino")).performTextReplacement("Treino leve")
        // A espera de 500 ms do buffer roda no looper principal: avança o relógio dele.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        waitFor { runBlocking { testDb.db.routineDao().byId(id)?.title } == "Treino leve" }
        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNode(hasText("Reverter alterações") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `edicao - titulo vazio mostra o erro e nao grava`() {
        val id = showSaved()

        compose.onNode(hasSetTextAction() and hasText("Treino")).performTextReplacement("")

        compose.onNodeWithText("Dê um título à rotina.").assertIsDisplayed()
        viewModel!!.flushPending()
        runBlocking { viewModel!!.awaitPendingWrites() }
        assertEquals("Treino", runBlocking { testDb.db.routineDao().byId(id)?.title })
    }

    @Test
    fun `edicao - Excluir rotina no menu exclui sem confirmacao, oferece Desfazer e fecha`() {
        val id = showSaved()
        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNode(hasText("Excluir rotina") and hasClickAction()).assertIsDisplayed().performClick()

        waitFor { closed == 1 && offers.pending.value != null }
        assertEquals(0, compose.onAllNodesWithText("Excluir rotina?").fetchSemanticsNodes().size)
        assertTrue(runBlocking { testDb.db.routineDao().byId(id)?.archivedAt } != null)
        assertEquals(UndoTarget.DeletedRoutine(id), offers.pending.value?.target)
    }

    @Test
    fun `criacao - Criar rotina no rodape, ligado so com titulo, grava e fecha`() {
        show(NEW_ROUTINE_ID)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Criar rotina").fetchSemanticsNodes().isNotEmpty() }
        val create = compose.onNode(hasText("Criar rotina") and hasClickAction())
        create.assertIsDisplayed().assertIsNotEnabled()
        assertTrue(runBlocking { testDb.db.routineDao().all() }.isEmpty())

        compose.onNode(hasSetTextAction() and hasText("Título")).performTextReplacement("Corrida")
        create.assertIsEnabled().performClick()

        waitFor { closed == 1 }
        assertEquals(listOf("Corrida"), runBlocking { testDb.db.routineDao().all() }.map { it.title })
    }

    private val isHeading = SemanticsMatcher("é cabeçalho") { it.config.getOrNull(SemanticsProperties.Heading) != null }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
