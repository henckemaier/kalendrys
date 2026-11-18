package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.TaskTiming
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testTaskRepository
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Semântica da lista de Tarefas (ADR-0002, 14.9; v1.8.0, E7), com a busca e os chips como itens da
 * lista desde a E5: o campo de busca lido pela dica, o Limpar só com texto, os chips com o estado de
 * seleção, o vazio da busca e os cabeçalhos de seção. Rolagem e foco ficam no [TaskListScrollTest];
 * linhas, deslize e Desfazer no [TaskListShellTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TaskListSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val tasks = testTaskRepository(testDb.db)
        runBlocking {
            tasks.addTask("Comprar pão")
            tasks.addTask("Pagar luz", timing = TaskTiming(date = LocalDate.now()))
        }
        val viewModel = testDb.track(
            TaskViewModel(tasks, ShoppingRepository(testDb.db), testUndoOffers(testDb, tasks = tasks)),
        )
        compose.setContent {
            KalendrysTheme { TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel) }
        }
        waitFor { compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS, condition)

    private val search: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction())

    private fun clearButtons() = compose.onAllNodes(hasContentDescription("Limpar busca")).fetchSemanticsNodes()

    private fun chip(label: String) = compose.onNode(hasText(label) and isSelectable())

    // --- (a) campo de busca ---

    @Test
    fun `campo de busca e um no so, lido pela dica Buscar tarefas`() {
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onNode(hasSetTextAction() and hasText("Buscar tarefas")).assertIsDisplayed()
    }

    @Test
    fun `sem texto nao ha Limpar busca, com texto aparece como botao e limpa`() {
        assertTrue("sem texto, sem Limpar", clearButtons().isEmpty())

        search.performTextInput("pão")
        waitFor { clearButtons().isNotEmpty() }
        val clear = compose.onNode(hasContentDescription("Limpar busca") and hasClickAction())
        assertEquals(Role.Button, clear.fetchSemanticsNode().config[SemanticsProperties.Role])

        clear.performClick()

        waitFor { clearButtons().isEmpty() }
        val text = search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("o campo ficou vazio", "", text)
        // A lista volta inteira.
        waitFor { compose.onAllNodes(hasText("Pagar luz")).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- (b) chips de filtro ---

    @Test
    fun `chips expoem selecionado e nao selecionado e alternam com o clique`() {
        val labels = listOf("Atrasadas", "Hoje", "Próximas", "Sem data")
        labels.forEach { chip(it).assertIsNotSelected() }

        chip("Hoje").performClick()
        waitFor { chip("Hoje").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false } }
        chip("Hoje").assertIsSelected()
        (labels - "Hoje").forEach { chip(it).assertIsNotSelected() }
        // O filtro vale: só a seção Hoje.
        waitFor { compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isEmpty() }

        chip("Hoje").performClick()
        waitFor { !chip("Hoje").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false } }
        chip("Hoje").assertIsNotSelected()
        waitFor { compose.onAllNodes(hasText("Comprar pão")).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- (c) vazio da busca ---

    @Test
    fun `filtro ativo sem tarefa mostra o vazio da busca com titulo e mensagem lidos`() {
        // Não há atrasada: só esse chip ligado não deixa nenhuma tarefa.
        chip("Atrasadas").performClick()

        waitFor { compose.onAllNodes(hasText("Nenhuma tarefa encontrada")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Nenhuma tarefa encontrada")).assertIsDisplayed()
        compose.onNode(hasText("Tente outro termo ou ajuste os filtros.")).assertIsDisplayed()
    }

    /**
     * NÃO VALE HOJE (E7): o título do [com.kalendrys.app.ui.components.EmptyState] é um Text comum,
     * sem heading. Produção não mexida; decisão com o Forja.
     */
    @Ignore("E7: titulo do vazio da busca nao e marcado como titulo (EmptyState sem heading)")
    @Test
    fun `vazio da busca tem o titulo marcado como titulo`() {
        chip("Atrasadas").performClick()

        waitFor { compose.onAllNodes(hasText("Nenhuma tarefa encontrada")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Nenhuma tarefa encontrada") and isHeading()).assertIsDisplayed()
    }

    // --- (d) cabeçalhos de seção ---

    /**
     * NÃO VALE HOJE (E7): o TaskSectionHeader é uma Row com dois Text soltos, sem heading e sem
     * mesclar: título e contagem são dois nós. Produção não mexida; decisão com o Forja.
     */
    @Ignore("E7: TaskSectionHeader sem heading e com a contagem num no separado")
    @Test
    fun `cabecalhos de secao marcados como titulo com a contagem no mesmo no`() {
        listOf("Hoje", "Sem data").forEach { title ->
            val header = compose.onNode(isHeading() and hasText(title, substring = true)).fetchSemanticsNode()
            val text = header.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            assertTrue("contagem no mesmo nó de $title: $text", text.contains("1"))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
