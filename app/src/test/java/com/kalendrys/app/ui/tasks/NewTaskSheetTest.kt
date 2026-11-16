package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L29 (O-2 da Coruja): em paisagem o "Nova tarefa" do topo do painel sumia sob a alça de arrastar,
 * porque rolava junto com os campos. Agora fica fixo fora da área que rola.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class NewTaskSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KalendrysTheme {
                    var draft by remember { mutableStateOf(NewTaskDraft.startingOn(LocalDate.now())) }
                    NewTaskSheet(
                        draft = draft,
                        onChange = { transform -> draft = transform(draft) },
                        onSave = {},
                        onCancel = {},
                        onDismiss = {},
                        lists = emptyList(),
                    )
                }
            }
        }
    }

    private fun titleStaysVisibleAfterScrollingToTheEnd() {
        compose.onNodeWithText("Nova tarefa").assertIsDisplayed()
        // Rolar até o fim (como o foco no campo fazia) não leva o título junto.
        compose.onNodeWithText("Salvar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nova tarefa").assertIsDisplayed()
    }

    @Test
    fun `paisagem com fonte 1_0 mantem o titulo do painel visivel`() {
        render(fontScale = 1f)
        titleStaysVisibleAfterScrollingToTheEnd()
    }

    @Test
    fun `paisagem com fonte 2_0 mantem o titulo do painel visivel`() {
        render(fontScale = 2f)
        titleStaysVisibleAfterScrollingToTheEnd()
    }
}
