package com.kalendrys.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.ShoppingProgress
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `ProgressLine` (ADR-0002, 4.2): texto "3/8", frase para o leitor e lista vazia escondida pela regra do domain. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProgressLineTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(progress: ShoppingProgress) = compose.setContent {
        KalendrysTheme { ProgressLine(progress, modifier = Modifier.testTag(TAG)) }
    }

    @Test
    fun `mostra 3 de 8 e le a frase inteira com o valor da barra`() {
        show(ShoppingProgress(picked = 3, total = 8))

        compose.onNodeWithText("3/8", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("3 de 8 marcados")
            .assertIsDisplayed()
            .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(3f / 8, 0f..1f)))
    }

    @Test
    fun `singular quando a lista tem um item`() {
        show(ShoppingProgress(picked = 0, total = 1))

        compose.onNodeWithContentDescription("0 de 1 marcado").assertIsDisplayed()
    }

    @Test
    fun `lista vazia nao desenha nada`() {
        show(ShoppingProgress.EMPTY)

        compose.onAllNodesWithText("0/0", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(TAG).assertDoesNotExist()
    }

    private companion object {
        const val TAG = "progress"
    }
}
