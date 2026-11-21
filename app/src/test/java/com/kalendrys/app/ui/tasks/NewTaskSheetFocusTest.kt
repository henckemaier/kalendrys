package com.kalendrys.app.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.domain.NewTaskDraft
import com.kalendrys.app.ui.theme.KalendrysTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Foco no Título do painel de nova tarefa (v1.9.0, ADR-0002, 15.7): pedido só na PRIMEIRA abertura.
 * Recriar o painel (girar a tela, estado restaurado) não puxa o foco de volta ao Título.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewTaskSheetFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val title = hasSetTextAction() and hasText("Título")
    private val notes = hasSetTextAction() and hasText("Observações (opcional)")

    @Test
    fun `abre com o foco no Titulo e recriar nao devolve o foco a ele`() {
        // O rascunho mora fora do painel (no app), como no NewTaskPanel: sobrevive à recriação.
        var draft by mutableStateOf(NewTaskDraft.startingOn(LocalDate.now()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            KalendrysTheme {
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
        compose.waitForIdle()
        compose.onNode(title).assertIsFocused()

        compose.onNode(notes).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(notes).assertIsFocused()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(title).assertIsNotFocused()
    }
}
