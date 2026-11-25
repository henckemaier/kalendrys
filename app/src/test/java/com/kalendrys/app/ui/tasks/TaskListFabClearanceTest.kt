package com.kalendrys.app.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.SystemAppClock
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TEST_CREATED_AT
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.testutil.testUndoOffers
import com.kalendrys.app.ui.theme.KalendrysTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guarda do fim da lista da aba Tarefas sob o botão flutuante (v1.10.0, E0, miúdo da 16.3): rolada até
 * o fim, a última tarefa termina inteira acima do topo do FAB, em 320dp e 360dp, com fonte 1.0 e 2.0.
 *
 * Verificado sem defeito: o achado da matriz da v1.9.0 ("o FAB cobre a última linha até rolar") é a
 * lista PARADA no topo, com o FAB sobre a linha visível de baixo, o comportamento normal de FAB sobre
 * lista (como o O-8 da Hoje). O layout não muda; este teste prende a folga do fim.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskListFabClearanceTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var viewModel: TaskViewModel
    private val titles = (1..14).map { "Tarefa número $it com um título comprido" }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val db = testDb.db
        runBlocking { titles.forEach { db.taskDao().insert(Task(title = it, createdAt = TEST_CREATED_AT)) } }
        viewModel = testDb.track(
            TaskViewModel(
                TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao(), SystemAppClock),
                ShoppingRepository(db, SystemAppClock),
                testUndoOffers(testDb),
                SystemAppClock,
            ),
        )
    }

    @After
    fun tearDown() = testDb.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private val fab = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Nova tarefa"))

    private fun assertLastRowClearOfFab(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme { TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel) }
            }
        }
        waitFor { compose.onAllNodes(hasText(titles.first(), substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasScrollToKeyAction()).performScrollToNode(hasText(titles.last()))
        // performScrollToNode só traz a linha para a tela; o fim de verdade (com a folga do FAB depois do
        // último item) é onde o dedo leva: arrasta até não rolar mais.
        repeat(6) {
            compose.onNode(hasScrollToKeyAction()).performTouchInput { swipeUp() }
            shadowOf(Looper.getMainLooper()).idle()
        }
        waitFor { compose.onAllNodes(fab, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        val last = compose.onNode(hasText(titles.last())).fetchSemanticsNode().boundsInRoot
        val fabTop = compose.onAllNodes(fab, useUnmergedTree = true).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        assertTrue(
            "fonte $fontScale: última linha (fundo ${last.bottom}) acima do FAB (topo $fabTop)",
            last.bottom <= fabTop,
        )
    }

    @Test
    fun `320dp com fonte 2_0 - a ultima linha fica acima do FAB`() = assertLastRowClearOfFab(2f)

    @Test
    fun `320dp com fonte 1_0 - a ultima linha fica acima do FAB`() = assertLastRowClearOfFab(1f)

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun `360dp com fonte 2_0 - a ultima linha fica acima do FAB`() = assertLastRowClearOfFab(2f)

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun `360dp com fonte 1_0 - a ultima linha fica acima do FAB`() = assertLastRowClearOfFab(1f)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
