package com.kalendrys.app.ui.today

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
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
 * Semântica da tela Hoje (ADR-0002, 14.9, critério de toda tela nova; v1.8.0, E2): um nó por linha,
 * estado e papel expostos, toda ação de gesto com ação equivalente, cabeçalhos marcados. Também: o
 * botão + abre o painel de nova tarefa e o menu tem "Todas as rotinas" e "Nova rotina".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TodayScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TodayViewModel
    private val calls = mutableListOf<String>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val repos = TodayRepositories(context, testDb.db)
        val today = LocalDate.now()
        runBlocking {
            repos.routines.add(Routine(title = "Treino", startMinute = 7 * 60, durationMinutes = 30, createdAt = TEST_CREATED_AT))
            repos.tasks.addTask("Ligar para o banco", timing = TaskTiming(date = today, time = LocalTime.of(23, 0)))
            repos.tasks.addTask("Comprar pão", timing = TaskTiming(date = today))
        }
        viewModel = testDb.track(repos.viewModel(DayOfWeek.SUNDAY))
        compose.setContent {
            KalendrysTheme {
                TodayScreen(
                    onOpenRoutine = { calls += "routine $it" },
                    onNewRoutine = { calls += "new routine" },
                    onOpenAllRoutines = { calls += "all routines" },
                    onOpenSettings = {},
                    onOpenTask = { calls += "task $it" },
                    onNewTask = { calls += "new task $it" },
                    onOpenException = { id, _ -> calls += "exception $id" },
                    viewModel = viewModel,
                )
            }
        }
        waitFor { compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    /** O nó (mesclado) da LINHA que mostra [text]: a que tem estado (o "A seguir" também pode citar o título). */
    private fun row(text: String): SemanticsNode =
        compose.onNode(hasText(text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
            .fetchSemanticsNode()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.actionLabels(): List<String?> =
        config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun `bloco de rotina e um no com estado, concluir acessivel e segurar com rotulo`() {
        val block = row("Treino")

        // Um nó só: o título e o horário estão no mesmo nó.
        assertTrue("textos ${block.texts()}", block.texts().any { it.startsWith("07:00") })
        assertEquals("Pendente", block.config[SemanticsProperties.StateDescription])
        // E3: "Pular este dia" também é ação direta da linha (além do menu do segurar).
        assertEquals(listOf<String?>("Concluir Treino", "Pular este dia"), block.actionLabels())
        assertNotNull("tocar abre", block.config.getOrNull(SemanticsActions.OnClick))
        assertEquals("Pular ou remarcar este dia", block.config[SemanticsActions.OnLongClick].label)
        // O círculo não é um segundo nó com a mesma ação.
        compose.onAllNodes(hasContentDescription("Concluir Treino")).assertCountEquals(0)

        // Concluir pela ação de acessibilidade muda o estado e o rótulo da ação.
        compose.runOnIdle { block.action("Concluir Treino").action() }
        waitFor { row("Treino").config.getOrNull(SemanticsProperties.StateDescription) == "Feita" }
        assertEquals(listOf<String?>("Desmarcar Treino", "Pular este dia"), row("Treino").actionLabels())

        // Segurar pela ação acessível abre o menu; "Remarcar este dia" abre a exceção do dia.
        compose.runOnIdle { row("Treino").config[SemanticsActions.OnLongClick].action?.invoke() }
        compose.onNodeWithText("Pular este dia").assertIsDisplayed()
        compose.onNodeWithText("Remarcar este dia").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue("chamadas $calls", calls.any { it.startsWith("exception ") }) }
    }

    private fun SemanticsNode.action(label: String) =
        config[SemanticsActions.CustomActions].single { it.label == label }

    @Test
    fun `tarefa da trilha e tarefa sem horario sao um no cada, com estado e concluir acessivel`() {
        listOf("Ligar para o banco", "Comprar pão").forEach { title ->
            val node = row(title)
            assertEquals(title, "Pendente", node.config[SemanticsProperties.StateDescription])
            assertEquals(title, listOf<String?>("Concluir $title"), node.actionLabels())
            assertNotNull(title, node.config.getOrNull(SemanticsActions.OnClick))
            compose.onAllNodes(hasContentDescription("Concluir $title")).assertCountEquals(0)
        }

        // E3: concluída sai de "Hoje" e vai para "Concluídas" (recolhida); aberta, a linha é Feita e desmarcável.
        compose.runOnIdle { row("Comprar pão").config[SemanticsActions.CustomActions].single().action() }
        waitFor { compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isEmpty() }
        val done = compose.onNode(heading and hasText("Concluídas")).fetchSemanticsNode()
        compose.runOnIdle { done.config[SemanticsActions.OnClick].action?.invoke() }
        waitFor { compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Feita", row("Comprar pão").config[SemanticsProperties.StateDescription])
        assertEquals(listOf<String?>("Desmarcar Comprar pão"), row("Comprar pão").actionLabels())
    }

    @Test
    fun `secao Hoje e marcada como titulo, com a contagem no mesmo no`() {
        val header = compose.onNode(heading and hasText("Hoje") and hasText("1")).fetchSemanticsNode()

        assertNotNull(header.config.getOrNull(SemanticsProperties.Heading))
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `dias da semana sao abas com o selecionado marcado e o deslize tem acoes equivalentes`() {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tab).assertCountEquals(7)
        compose.onAllNodes(tab and isSelected()).assertCountEquals(1)
        compose.onNode(tab and isSelected()).assertIsSelected()

        val strip = compose.onNode(
            SemanticsMatcher("faixa com ações da semana") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label } ==
                    listOf("Semana anterior", "Próxima semana")
            },
        ).fetchSemanticsNode()
        assertNotNull(strip)
    }

    @Test
    fun `dia futuro nao oferece concluir`() {
        compose.runOnIdle { viewModel.select(LocalDate.now().plusDays(1)) }
        waitFor { viewModel.uiState.value.selectedDate == LocalDate.now().plusDays(1) }
        waitFor { compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty() }

        // Sem concluir em dia futuro; pular o dia continua possível (como na tela de exceção).
        assertEquals(listOf<String?>("Pular este dia"), row("Treino").actionLabels())
        assertEquals("Pendente", row("Treino").config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `botao + pede o painel de nova tarefa com o dia mostrado`() {
        compose.onNodeWithContentDescription("Nova tarefa em ", substring = true).performClick()

        // O painel mora no topo da navegação (E6): a tela só pede.
        assertEquals(listOf("new task ${java.time.LocalDate.now()}"), calls)
        compose.onAllNodesWithText("Título").assertCountEquals(0)
    }

    @Test
    fun `menu tem Todas as rotinas e Nova rotina`() {
        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNodeWithText("Todas as rotinas").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("all routines"), calls) }

        compose.onNodeWithContentDescription("Mais opções").performClick()
        compose.onNodeWithText("Nova rotina").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("all routines", "new routine"), calls) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
