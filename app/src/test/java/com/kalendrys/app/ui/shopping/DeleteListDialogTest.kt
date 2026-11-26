package com.kalendrys.app.ui.shopping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Excluir lista?" com corpo (achado da Coruja na T3, v1.10.0): diz o que sai e que não volta. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeleteListDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `titulo e corpo explicam que lista e itens saem sem volta`() {
        compose.setContent {
            KalendrysTheme { DeleteListDialog(name = "Feira", onConfirm = {}, onDismiss = {}) }
        }

        compose.onNodeWithText("Excluir lista?").assertIsDisplayed()
        compose.onNodeWithText("A lista \"Feira\" e os itens dela serão apagados. Isso não pode ser desfeito.").assertIsDisplayed()
    }
}
