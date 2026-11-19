package com.kalendrys.app.ui.today

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.TaskTiming
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
import org.robolectric.annotation.GraphicsMode

/**
 * Hoje (v1.8.0, E3): o estado vazio (primeira tela de quem instala) fala do dia e oferece Nova
 * tarefa e Nova rotina; em paisagem, com fonte 1.0 e 2.0, a tela mostra conteúdo e rola até o fim
 * (a tela inteira é uma lista, a faixa da semana rola junto).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
// Medida de texto de verdade: com fonte 2.0 as linhas crescem como no aparelho.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayEmptyAndLandscapeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var testDb: ViewModelTestDatabase
    private lateinit var repos: TodayRepositories
    private val calls = mutableListOf<String>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDb = ViewModelTestDatabase(context)
        repos = TodayRepositories(context, testDb.db)
    }

    @After
    fun tearDown() = testDb.close()

    private fun show(fontScale: Float = 1f) {
        val viewModel = testDb.track(repos.viewModel(DayOfWeek.SUNDAY))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    TodayScreen(
                        onOpenRoutine = {},
                        onNewRoutine = { calls += "new routine" },
                        onOpenAllRoutines = {},
                        onOpenSettings = {},
                        onOpenTask = {},
                        onOpenException = { _, _ -> },
                        onNewTask = { calls += "new task $it" },
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.uiState.value.isLoaded
        }
        compose.waitForIdle()
    }

    /** Um dia cheio: vários blocos, tarefas em todas as seções e uma concluída (a última seção). */
    private fun aFullDay() = runBlocking {
        val today = LocalDate.now()
        listOf("Acordar", "Treino", "Trabalho", "Almoço", "Leitura", "Jantar").forEachIndexed { i, title ->
            repos.routines.add(Routine(title = title, startMinute = (6 + i * 2) * 60, durationMinutes = 45))
        }
        repos.tasks.addTask("Pagar boleto", timing = TaskTiming(date = today.minusDays(2)))
        repos.tasks.addTask("Comprar pão", timing = TaskTiming(date = today))
        repos.tasks.addTask("Ligar para o banco", timing = TaskTiming(date = today, time = LocalTime.of(23, 30)))
        repos.tasks.addTask("Ideias para o fim de semana")
        repos.tasks.logCompletion(repos.tasks.addTask("Regar as plantas", timing = TaskTiming(date = today)).id)
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `vazio fala do dia e oferece Nova tarefa e Nova rotina`() {
        show()

        compose.onNodeWithText("Nada no seu dia ainda").assertIsDisplayed()
        // O botão do vazio (o FAB também diz "Nova tarefa", mas com a descrição do dia).
        val emptyNewTask = hasText("Nova tarefa") and hasClickAction() and
            !hasContentDescription("Nova tarefa em ", substring = true)
        // Em paisagem de 360dp o vazio rola (não corta): leva o botão até a tela antes de tocar.
        compose.onNode(hasText("Nova rotina") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("new routine"), calls)

        compose.onNode(emptyNewTask).performScrollTo().assertIsDisplayed().performClick()
        // O painel mora no topo da navegação (E6): a tela só pede, com o dia mostrado.
        assertEquals(listOf("new routine", "new task ${java.time.LocalDate.now()}"), calls)
    }

    private fun assertLandscapeShowsAndScrolls(fontScale: Float) {
        aFullDay()
        show(fontScale)

        // Mostra conteúdo logo de cara (não só a barra e a faixa).
        compose.onNode(heading and hasText("Progresso do dia")).assertIsDisplayed()
        // A faixa da semana rola junto: a lista vai até a última seção.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("section_done")
        compose.onNode(heading and hasText("Concluídas")).assertIsDisplayed()
    }

    @Test
    fun `paisagem com fonte 1_0 mostra conteudo e rola ate o fim`() = assertLandscapeShowsAndScrolls(1f)

    @Test
    fun `paisagem com fonte 2_0 mostra conteudo e rola ate o fim`() = assertLandscapeShowsAndScrolls(2f)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
