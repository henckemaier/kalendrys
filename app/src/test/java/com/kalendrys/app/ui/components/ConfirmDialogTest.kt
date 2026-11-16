package com.kalendrys.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [ConfirmDialog] (ADR-0002, 4.2): textos, confirmar e cancelar. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConfirmDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmed = 0
    private var dismissed = 0

    private fun setDialog() {
        compose.setContent {
            KalendrysTheme {
                ConfirmDialog(
                    title = "Excluir lista?",
                    text = "A lista e os itens dela serão apagados.",
                    confirmLabel = "Excluir",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun `mostra titulo, texto e os dois botoes`() {
        setDialog()

        compose.onNodeWithText("Excluir lista?").assertIsDisplayed()
        compose.onNodeWithText("A lista e os itens dela serão apagados.").assertIsDisplayed()
        compose.onNodeWithText("Excluir").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").assertIsDisplayed()
    }

    @Test
    fun `confirmar chama so onConfirm`() {
        setDialog()

        compose.onNodeWithText("Excluir").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `cancelar chama so onDismiss`() {
        setDialog()

        compose.onNodeWithText("Cancelar").performClick()

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }
}
