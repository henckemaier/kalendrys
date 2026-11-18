package com.kalendrys.app.ui.tasks

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.domain.ReminderRepeat
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O-5 (v1.8.0): a tarefa repetida que NÃO acontece hoje (em "Próximas") não mostra o círculo de
 * concluir. No mesmo espaço fica o ícone de repetição, decorativo: sem toque nem descrição próprios,
 * e tocar ali abre a tarefa como o resto da linha. O leitor de tela não encontra um Concluir
 * desabilitado. A repetida que acontece hoje continua com o Concluir.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TaskListRepeatingRowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private val opened = mutableListOf<Long>()
    private var later = 0L
    private var todayId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val tasks = testTaskRepository(testDb.db)
        val today = LocalDate.now()
        runBlocking {
            // Semanal a partir de depois de amanhã: a próxima vez não é hoje.
            later = tasks.addTask("Treino semanal", timing = TaskTiming(date = today.plusDays(2), repeat = ReminderRepeat.WEEKLY)).id
            todayId = tasks.addTask("Remédio", timing = TaskTiming(date = today, repeat = ReminderRepeat.DAILY)).id
        }
        val viewModel = testDb.track(
            TaskViewModel(tasks, ShoppingRepository(testDb.db), testUndoOffers(testDb, tasks = tasks)),
        )
        compose.setContent {
            KalendrysTheme { TaskListScreen(onOpenTask = { opened += it }, onOpenSettings = {}, viewModel = viewModel) }
        }
        compose.waitUntil(TIMEOUT_MS) {
            listOf("Treino semanal", "Remédio").all { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @After
    fun tearDown() = testDb.close()

    /** O nó da linha (mesclado, com o toque de abrir). */
    private fun row(title: String): SemanticsNode = compose.onNode(hasText(title) and hasClickAction()).fetchSemanticsNode()

    /** Nós cujos limites caem dentro da linha de [title] (por padrão na árvore sem mesclar, que vê cada ícone). */
    private fun insideRow(title: String, matcher: SemanticsMatcher, unmerged: Boolean = true): List<SemanticsNode> {
        val bounds = row(title).boundsInRoot
        return compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes()
            .filter { bounds.containsRect(it.boundsInRoot) }
    }

    private fun Rect.containsRect(other: Rect) =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    @Test
    fun `repetida fora do dia nao tem Concluir nem outro alvo de toque na linha`() {
        assertTrue("sem nó de Concluir", insideRow("Treino semanal", hasContentDescription("Concluir tarefa")).isEmpty())
        // Só a própria linha é clicável ali dentro: o ícone de repetição não tem toque próprio.
        val rowId = row("Treino semanal").id
        val others = insideRow("Treino semanal", hasClickAction()).filter { it.id != rowId }
        assertTrue("só a linha: $others", others.isEmpty())
    }

    @Test
    fun `tocar no icone de repeticao abre a tarefa`() {
        compose.onNode(hasText("Treino semanal") and hasClickAction()).performTouchInput {
            // O ícone ocupa os primeiros 48dp da linha (depois do respiro inicial).
            click(Offset(28.dp.toPx(), centerY))
        }

        compose.runOnIdle { assertEquals(listOf(later), opened) }
    }

    @Test
    fun `repetida que acontece hoje continua com o Concluir`() {
        // Na árvore mesclada o botão leva a descrição do ícone.
        val complete = insideRow("Remédio", hasContentDescription("Concluir tarefa") and hasClickAction(), unmerged = false)
        assertEquals(1, complete.size)
        compose.onNode(SemanticsMatcher("Concluir do Remédio") { it.id == complete.single().id }).assertIsEnabled()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
