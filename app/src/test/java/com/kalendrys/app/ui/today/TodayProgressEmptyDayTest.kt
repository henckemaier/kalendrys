package com.kalendrys.app.ui.today

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.TodayOverview
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Progresso do dia (v1.9.0, ADR-0002, 15.7): em dia sem itens não aparece "Feitos: 0 de 0" nem a
 * barra vazia; ficam o título da seção e o "Nada pendente". Com itens, segue como antes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TodayProgressEmptyDayTest {

    @get:Rule
    val compose = createComposeRule()

    private val progressBar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun show(done: Int, pending: Int) {
        val overview = TodayOverview(date = LocalDate.now(), pending = emptyList(), doneCount = done)
        compose.setContent { KalendrysTheme { TodayProgress(overview, done = done, pending = pending) } }
        compose.waitForIdle()
    }

    @Test
    fun `dia sem itens - sem 0 de 0 e sem barra, com Nada pendente`() {
        show(done = 0, pending = 0)

        compose.onNodeWithText("Progresso do dia").assertIsDisplayed()
        compose.onNodeWithText("Nada pendente", substring = true, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Feitos", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(progressBar, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `dia com tudo feito - Feitos e barra continuam`() {
        show(done = 2, pending = 0)

        compose.onNode(hasText("Feitos: 2 de 2", substring = true), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(progressBar, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
