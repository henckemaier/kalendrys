package com.kalendrys.app.ui.today

import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.ui.navigation.UndoOfferEffect
import com.kalendrys.app.ui.components.LocalAppSnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.SnackbarHostState
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.R
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Semântica das partes novas da Hoje (ADR-0002, 14.9; v1.8.0, E3): seções com cabeçalho, Sem data
 * recolhida com estado e ação, Pular pelo menu do bloco com a mensagem de Desfazer, bloco pulado com
 * estado "Pulado" e "Desfazer pular", e o título com o dia fora da data de hoje.
 *
 * Relógio do teste parado: a mensagem longa (10 s) não pode vencer enquanto o teste confere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TodayContentSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TodayViewModel
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        // A oferta de Desfazer da Hoje roda no escopo do app de teste (o close espera; v1.9.0, E4).
        val repos = TodayRepositories(context, testDb.db, testDb.appScope())
        runBlocking {
            repos.routines.add(Routine(title = "Treino", startMinute = 7 * 60, durationMinutes = 30, createdAt = TEST_CREATED_AT))
            repos.tasks.addTask("Ideias para o fim de semana")
            repos.tasks.addTask("Comprar pão", timing = TaskTiming(date = LocalDate.now()))
        }
        viewModel = testDb.track(repos.viewModel(DayOfWeek.SUNDAY))
        compose.setContent {
            // Como no KalendrysNavHost: a mensagem de Desfazer da Hoje é a da oferta do app (v1.9.0, E4),
            // mostrada pelo UndoOfferEffect na caixa de mensagens do app.
            val appSnackbar = remember { SnackbarHostState() }
            KalendrysTheme {
                CompositionLocalProvider(LocalAppSnackbarHostState provides appSnackbar) {
                UndoOfferEffect(repos.undoOffers, appSnackbar)
                TodayScreen(
                    onOpenRoutine = {},
                    onNewRoutine = {},
                    onOpenAllRoutines = {},
                    onOpenSettings = {},
                    onOpenTask = {},
                    onOpenException = { _, _ -> },
                    viewModel = viewModel,
                )
                }
            }
        }
        compose.mainClock.autoAdvance = false
        waitFor { compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    /** Avisa as escritas fora do quadro, drena o looper (Room volta ao Main) e avança um quadro. */
    private fun step() {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        step()
        condition()
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNode = compose.onNode(matcher).fetchSemanticsNode()

    /** A LINHA que mostra [text]: a que tem estado (o "A seguir" também pode citar o título). */
    private fun row(text: String): SemanticsNode =
        node(hasText(text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))

    private fun SemanticsNode.actionLabels(): List<String?> =
        config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun SemanticsNode.state(): String? = config.getOrNull(SemanticsProperties.StateDescription)

    private fun SemanticsNode.runAction(label: String) {
        compose.runOnUiThread { config[SemanticsActions.CustomActions].single { it.label == label }.action() }
    }

    @Test
    fun `progresso, proximo item e secoes sao cabecalhos`() {
        listOf("Progresso do dia", "A seguir", "Hoje", "Sem data").forEach { title ->
            assertTrue(title, compose.onAllNodes(heading and hasText(title)).fetchSemanticsNodes().isNotEmpty())
        }
        // O progresso é um nó com os números do retrato do dia: nada feito, 3 pendentes (2 tarefas e 1 rotina).
        assertTrue(compose.onAllNodes(hasText("Feitos: 0 de 3")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Sem data comeca recolhida, com estado e acao de mostrar no cabecalho`() {
        val header = node(heading and hasText("Sem data"))
        assertEquals("Recolhida", header.state())
        assertEquals("Mostrar Sem data", header.config[SemanticsActions.OnClick].label)
        assertEquals(0, compose.onAllNodesWithText("Ideias para o fim de semana").fetchSemanticsNodes().size)

        compose.runOnUiThread { header.config[SemanticsActions.OnClick].action?.invoke() }
        waitFor { compose.onAllNodesWithText("Ideias para o fim de semana").fetchSemanticsNodes().isNotEmpty() }

        val opened = node(heading and hasText("Sem data"))
        assertEquals("Aberta", opened.state())
        assertEquals("Recolher Sem data", opened.config[SemanticsActions.OnClick].label)
        assertEquals("Pendente", row("Ideias para o fim de semana").state())
    }

    @Test
    fun `pular pelo menu do bloco mostra a mensagem e Desfazer volta o dia`() {
        compose.runOnUiThread { row("Treino").config[SemanticsActions.OnLongClick].action?.invoke() }
        // O menu ancorado no bloco (a ação da linha com o mesmo rótulo não é texto na tela).
        waitFor { compose.onAllNodesWithText("Pular este dia").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remarcar este dia").assertIsDisplayed()
        compose.onNode(hasText("Pular este dia") and hasClickAction()).performClick()

        waitFor { compose.onAllNodesWithText("Rotina pulada neste dia").fetchSemanticsNodes().isNotEmpty() }
        waitFor { row("Treino").state() == "Pulado" }
        compose.onNodeWithText("Rotina pulada neste dia").assertIsDisplayed()
        val skipped = row("Treino")
        assertEquals("pulado não se conclui", listOf<String?>("Desfazer pular"), skipped.actionLabels())

        compose.onNode(hasText("Desfazer") and hasClickAction()).performClick()
        waitFor { row("Treino").state() == "Pendente" }
        assertEquals(listOf<String?>("Concluir Treino", "Pular este dia"), row("Treino").actionLabels())
    }

    @Test
    fun `bloco pulado tem Desfazer pular acessivel e volta a pendente`() {
        row("Treino").runAction("Pular este dia")
        waitFor { row("Treino").state() == "Pulado" }

        row("Treino").runAction("Desfazer pular")
        waitFor { row("Treino").state() == "Pendente" }
    }

    @Test
    fun `fora da data de hoje o titulo e o dia mostrado e o botao Hoje aparece`() {
        val tomorrow = LocalDate.now().plusDays(1)
        compose.runOnUiThread { viewModel.select(tomorrow) }
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val weekday = res.getStringArray(R.array.weekday_short)[tomorrow.dayOfWeek.value % 7]
        val month = res.getStringArray(R.array.month_short)[tomorrow.monthValue - 1]
        val title = "$weekday, ${tomorrow.dayOfMonth} $month"

        waitFor { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(node(hasText("Hoje") and hasClickAction()))
        // Em outro dia não há progresso nem "A seguir" (são da data de hoje).
        assertEquals(0, compose.onAllNodes(heading and hasText("Progresso do dia")).fetchSemanticsNodes().size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
