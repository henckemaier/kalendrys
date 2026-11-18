package com.kalendrys.app.ui.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.NewTaskDraftStore
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.data.local.entity.ShoppingItem
import com.kalendrys.app.data.local.entity.ShoppingList
import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.testutil.FakeReminderScheduler
import com.kalendrys.app.testutil.TodayRepositories
import com.kalendrys.app.testutil.ViewModelTestDatabase
import com.kalendrys.app.ui.tasks.TaskListScreen
import com.kalendrys.app.ui.tasks.TaskViewModel
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.today.TodayScreen
import com.kalendrys.app.ui.today.TodayViewModel
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-1 (v1.7.1): o contador "2/3" da lista anexada aparece inteiro nas abas Rotinas e Tarefas em
 * retrato de 360dp, com fonte 1.0, 1.3 e 2.0. "Inteiro" = a etiqueta na linha tem a mesma largura
 * que ela mesma sozinha com espaço de sobra (a referência invisível desenhada junto). Árvore não
 * mesclada: a linha da rotina junta a semântica dos filhos num nó só.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
// Medida de texto de verdade: no modo LEGACY o Robolectric não mede o texto e a fonte não muda nada.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShoppingProgressTagFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var testDb: ViewModelTestDatabase
    private val description = "Lista de compras: 2 de 3 marcados"
    private val inRow = hasContentDescription(description) and !hasTestTag(REFERENCE)

    @Before
    fun setup() {
        testDb = ViewModelTestDatabase(context)
        runBlocking {
            val db = testDb.db
            val listId = db.shoppingListDao().insert(ShoppingList(name = "Mercado"))
            db.shoppingItemDao().insertAll(
                listOf(
                    ShoppingItem(listId = listId, name = "Arroz", picked = true, position = 0),
                    ShoppingItem(listId = listId, name = "Feijão", picked = true, position = 1),
                    ShoppingItem(listId = listId, name = "Café", position = 2),
                ),
            )
            db.routineDao().insert(
                Routine(
                    title = "Compras da semana no mercado do bairro com a família",
                    startMinute = 10 * 60,
                    durationMinutes = 90,
                    remind = true,
                    createdAt = 0,
                    shoppingListId = listId,
                ),
            )
            db.taskDao().insert(
                Task(
                    title = "Passar no mercado e conferir a lista inteira de compras",
                    location = "Supermercado do bairro, perto da praça",
                    shoppingListId = listId,
                ),
            )
        }
    }

    @After
    fun tearDown() = testDb.close()

    private fun taskRepository() = testDb.db.let { db ->
        TaskRepository(db.taskDao(), db.completionLogDao(), FakeReminderScheduler(context), db.shoppingItemDao())
    }

    private fun routinesViewModel(): TodayViewModel =
        testDb.track(TodayRepositories(context, testDb.db).viewModel(DayOfWeek.SUNDAY))

    private fun taskViewModel(): TaskViewModel =
        testDb.track(TaskViewModel(taskRepository(), ShoppingRepository(testDb.db), NewTaskDraftStore()))

    private var fontScale by mutableFloatStateOf(1f)

    /** Uma composição só; cada escala troca a fonte e confere a etiqueta medida de novo. */
    private fun showAtEachScale(label: String, screen: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    Box(Modifier.fillMaxSize()) {
                        screen()
                        // Referência: a mesma etiqueta com a largura toda disponível (mede o natural), invisível.
                        ShoppingProgressTag(
                            progress = ShoppingProgress(picked = 2, total = 3),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .alpha(0f)
                                .testTag(REFERENCE),
                        )
                    }
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(inRow, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        for (scale in SCALES) {
            fontScale = scale
            compose.waitForIdle()
            assertWholeTag("$label fonte $scale")
        }
    }

    private fun assertWholeTag(label: String) {
        val expected = compose.onNodeWithTag(REFERENCE, useUnmergedTree = true).fetchSemanticsNode().size.width
        val actual = compose.onNode(inRow, useUnmergedTree = true).fetchSemanticsNode().size.width
        assertEquals("$label: largura da etiqueta na linha", expected, actual)
    }

    @Test
    fun `aba Rotinas mostra 2 de 3 inteiro com fonte 1_0, 1_3 e 2_0`() {
        val viewModel = routinesViewModel()
        showAtEachScale("Rotinas") {
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

    @Test
    fun `aba Tarefas mostra 2 de 3 inteiro com fonte 1_0, 1_3 e 2_0`() {
        val viewModel = taskViewModel()
        showAtEachScale("Tarefas") { TaskListScreen(onOpenTask = {}, onOpenSettings = {}, viewModel = viewModel) }
    }

    private companion object {
        const val REFERENCE = "referencia"
        const val TIMEOUT_MS = 5_000L
        val SCALES = listOf(1f, 1.3f, 2f)
    }
}
