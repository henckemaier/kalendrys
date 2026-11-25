package com.kalendrys.app.ui.routines

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.testutil.FakeRoutineAlarms
import com.kalendrys.app.testutil.FakeRoutineNotifications
import com.kalendrys.app.testutil.InMemoryRoutineCatchUpMark
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.UndoOffers
import com.kalendrys.app.UndoTarget
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import com.kalendrys.app.ui.navigation.UndoOfferEffect
import com.kalendrys.app.ui.theme.KalendrysTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Semântica de "Todas as rotinas" (ADR-0002, 14.9, critério de toda tela nova): um nó por linha,
 * estado (Pausada) e papel expostos, toda ação com equivalente acessível, cabeçalhos marcados. E o
 * Desfazer do "Excluir rotina" (arquivar por baixo, P8) devolve a rotina.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AllRoutinesScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repository: RoutineRepository
    private lateinit var offers: UndoOffers
    private val opened = mutableListOf<Long>()
    private var newRoutines = 0
    private var backs = 0
    private var treino = 0L
    private var violao = 0L

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        repository = RoutineRepository(
            db.routineDao(),
            db.routineCompletionDao(),
            db.routineOverrideDao(),
            FakeRoutineAlarms(context),
            FakeRoutineNotifications(context),
            db.shoppingItemDao(),
            InMemoryRoutineCatchUpMark(),
            clock = SystemAppClock,
        )
        runBlocking {
            // Categoria de antes da v1.10.0: fica no banco, mas a linha não a mostra (16.10.5).
            val academia = db.routineCategoryDao().insert(RoutineCategory(name = "Academia", color = "GREEN"))
            treino = repository.add(Routine(title = "Treino", startMinute = 7 * 60, durationMinutes = 60, createdAt = 0L, categoryId = academia)).id
            val v = repository.add(Routine(title = "Violão", startMinute = 19 * 60, createdAt = 0L))
            repository.pause(v)
            violao = v.id
        }
        offers = testUndoOffers(testDb, repository)
        val viewModel = testDb.track(AllRoutinesViewModel(repository, offers))
        compose.setContent {
            KalendrysTheme {
                // Como o KalendrysNavHost: a mensagem do app (NB-E3-C), com o coletor único fora da tela.
                val app = remember { SnackbarHostState() }
                CompositionLocalProvider(LocalAppSnackbarHostState provides app) {
                    UndoOfferEffect(offers, app)
                    AllRoutinesScreen(
                        onBack = { backs++ },
                        onOpenRoutine = { opened += it },
                        onNewRoutine = { newRoutines++ },
                        viewModel = viewModel,
                    )
                }
            }
        }
        waitForRow(treino)
        waitForRow(violao)
    }

    @After
    fun tearDown() = testDb.close()

    private fun rowTag(id: Long) = "routine_row_$id"

    private fun waitForRow(id: Long) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(rowTag(id))).fetchSemanticsNodes().isNotEmpty() }

    private fun row(id: Long): SemanticsNode = compose.onNodeWithTag(rowTag(id)).fetchSemanticsNode()

    private fun actionLabels(id: Long): List<String> =
        row(id).config[SemanticsActions.CustomActions].map { it.label }

    private fun runAction(id: Long, label: String) {
        val action = row(id).config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    private fun stateOf(id: Long): String? = row(id).config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `cada linha e um no so - papel de botao, rotulo de editar e nenhum outro alvo dentro`() {
        val node = row(treino)

        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        assertEquals("Editar Treino", node.config[SemanticsActions.OnClick].label)
        // Título, horário e dias lidos juntos, no mesmo nó; sem o nome da categoria (v1.10.0).
        val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertEquals(true, text.contains("Treino") && text.contains("07:00") && text.contains("Todo dia"))
        assertEquals(false, text.contains("Academia"))
        // O menu de três pontos não vira um segundo nó focável.
        assertEquals(0, compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(rowTag(treino)))).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Mais opções para Treino")).fetchSemanticsNodes().size)
    }

    @Test
    fun `estado Pausada exposto so na pausada, com as acoes de cada estado`() {
        assertEquals("Pausada", stateOf(violao))
        assertNull(stateOf(treino))

        assertEquals(listOf("Pausar", "Excluir rotina"), actionLabels(treino))
        assertEquals(listOf("Retomar", "Excluir rotina"), actionLabels(violao))
    }

    @Test
    fun `cabecalhos Ativas e Pausadas marcados`() {
        compose.onNode(hasText("Ativas") and isHeading()).fetchSemanticsNode()
        compose.onNode(hasText("Pausadas") and isHeading()).fetchSemanticsNode()
    }

    @Test
    fun `acao Pausar da linha pausa e Retomar devolve`() {
        runAction(treino, "Pausar")
        compose.waitUntil(TIMEOUT_MS) { stateOf(treino) == "Pausada" }
        assertEquals(listOf("Retomar", "Excluir rotina"), actionLabels(treino))

        runAction(treino, "Retomar")
        compose.waitUntil(TIMEOUT_MS) { stateOf(treino) == null }
    }

    @Test
    fun `excluir pela acao da linha publica a oferta do app e o Desfazer dela devolve a rotina`() {
        runAction(treino, "Excluir rotina")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(rowTag(treino))).fetchSemanticsNodes().isEmpty() }
        // A mensagem é a do app (NB-E3-C): a mesma oferta do editor, não um aviso próprio da tela.
        compose.waitUntil(TIMEOUT_MS) { offers.pending.value?.target == UndoTarget.DeletedRoutine(treino) }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Rotina excluída")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodes(hasText("Rotina excluída")).fetchSemanticsNodes().size)

        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()

        waitForRow(treino)
        assertNull(runBlocking { testDb.db.routineDao().byId(treino) }!!.archivedAt)
    }

    @Test
    fun `menu de tres pontos visivel faz o mesmo que as acoes`() {
        val menus = compose.onAllNodes(hasTestTag(MENU_TAG), useUnmergedTree = true)
        menus[0].performClick()
        compose.onNodeWithText("Pausar").performClick()

        compose.waitUntil(TIMEOUT_MS) { stateOf(treino) == "Pausada" }
    }

    @Test
    fun `toque na linha edita, FAB cria e voltar volta`() {
        compose.onNodeWithTag(rowTag(treino)).performClick()
        compose.onNodeWithContentDescription("Nova rotina").performClick()
        compose.onNodeWithContentDescription("Voltar").performClick()

        compose.runOnIdle {
            assertEquals(listOf(treino), opened)
            assertEquals(1, newRoutines)
            assertEquals(1, backs)
        }
    }

    @Test
    fun `sem filtro por categoria - nenhum chip com o nome dela`() {
        waitForRow(treino)
        waitForRow(violao)
        assertEquals(0, compose.onAllNodesWithText("Academia").fetchSemanticsNodes().size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
