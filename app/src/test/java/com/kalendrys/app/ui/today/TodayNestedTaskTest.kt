package com.kalendrys.app.ui.today

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tarefa aninhada no bloco, na tela da Hoje (v1.10.0, ADR-0002 16.10.8 item 7): o bloco é um nó, a
 * tarefa de dentro é outro nó que diz "dentro de <rotina>", e a hora de fim do bloco vai para baixo
 * dela (a coluna lê 07:00, 07:00... 07:30, 08:00), sem virar item lido.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-port")
class TodayNestedTaskTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        val repos = TodayRepositories(context, testDb.db)
        val today = LocalDate.now()
        runBlocking {
            repos.routines.add(Routine(title = "Treino", startMinute = 7 * 60, durationMinutes = 60, createdAt = TEST_CREATED_AT))
            repos.tasks.addTask("Alongar", timing = TaskTiming(date = today, time = LocalTime.of(7, 30)))
            repos.tasks.addTask("Ligar para o banco", timing = TaskTiming(date = today, time = LocalTime.of(9, 0)))
        }
        val viewModel = testDb.track(repos.viewModel(DayOfWeek.SUNDAY))
        compose.setContent {
            KalendrysTheme {
                TodayScreen(
                    onOpenRoutine = {},
                    onNewRoutine = {},
                    onOpenAllRoutines = {},
                    onOpenSettings = {},
                    onOpenTask = {},
                    onNewTask = {},
                    onOpenException = { _, _ -> },
                    viewModel = viewModel,
                )
            }
        }
        waitFor { compose.onAllNodesWithText("Treino").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() = testDb.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private val hasState = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    @Test
    fun `tarefa de dentro diz dentro de e a de fora nao`() {
        val inside = compose.onNode(hasContentDescription("Alongar", substring = true) and hasState).fetchSemanticsNode()
        assertEquals("Tarefa, 07:30, Alongar, dentro de Treino", inside.config[SemanticsProperties.ContentDescription].first())

        val outside = compose.onNode(hasContentDescription("Ligar para o banco", substring = true) and hasState).fetchSemanticsNode()
        assertEquals("Tarefa, 09:00, Ligar para o banco", outside.config[SemanticsProperties.ContentDescription].first())
    }

    @Test
    fun `o bloco continua um no so, e o fim dele nao e lido`() {
        val block = compose.onAllNodes(hasText("Treino", substring = true) and hasState).fetchSemanticsNodes()
        assertEquals(1, block.size)
        // A hora de fim (08:00) está desenhada embaixo da tarefa, mas não é uma linha lida (o bloco já diz o
        // horário). O "08:00" do intervalo livre que vem depois é outra coisa e continua.
        val read = compose.onAllNodesWithText("08:00").fetchSemanticsNodes().filter { it.config.getOrNull(SemanticsProperties.StateDescription) != null }
        assertEquals(0, read.size)
    }
}
