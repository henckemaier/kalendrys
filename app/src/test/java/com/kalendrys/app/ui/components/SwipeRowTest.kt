package com.kalendrys.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.ui.theme.KalendrysTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [SwipeRow] (ADR-0002, 4.2): ações de acessibilidade equivalentes e o deslize de verdade. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SwipeRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val haptics = mutableListOf<HapticFeedbackType>()

    private val fakeHaptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            haptics += hapticFeedbackType
        }
    }

    private fun setRow(withStart: Boolean = true, withEnd: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides fakeHaptics) {
                KalendrysTheme {
                    SwipeRow(
                        modifier = Modifier.width(360.dp),
                        startAction = if (withStart) {
                            SwipeAction("Concluir", Icons.Outlined.CheckCircle, HapticEvent.Complete) { calls += "concluir" }
                        } else {
                            null
                        },
                        endAction = if (withEnd) {
                            SwipeAction("Excluir", Icons.Outlined.Delete, HapticEvent.Delete) { calls += "excluir" }
                        } else {
                            null
                        },
                    ) { actions ->
                        ListRow(title = "Pagar o aluguel", supporting = "Hoje", modifier = actions, onClick = { calls += "abrir" })
                    }
                }
            }
        }
    }

    /** Ações personalizadas do nó mesclado que o leitor de tela foca (o da linha). */
    private fun customActions() =
        compose.onNodeWithText("Pagar o aluguel", useUnmergedTree = false)
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    @Test
    fun `as acoes personalizadas existem no item focado, com os rotulos dados`() {
        setRow()

        assertEquals(listOf("Concluir", "Excluir"), customActions().map { it.label })
    }

    @Test
    fun `as acoes personalizadas disparam o callback certo`() {
        setRow()

        compose.runOnIdle { customActions().single { it.label == "Excluir" }.action() }
        compose.runOnIdle { customActions().single { it.label == "Concluir" }.action() }

        compose.runOnIdle { assertEquals(listOf("excluir", "concluir"), calls) }
    }

    @Test
    fun `lado desligado nao vira acao`() {
        setRow(withStart = false)

        assertEquals(listOf("Excluir"), customActions().map { it.label })
    }

    @Test
    fun `deslizar para a esquerda exclui, com retorno tatil de exclusao`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("excluir"), calls)
        assertEquals(listOf(Haptics.typeOf(HapticEvent.Delete)), haptics)
    }

    @Test
    fun `deslizar para a direita conclui, com retorno tatil de conclusao`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(listOf("concluir"), calls)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics)
    }

    @Test
    fun `depois do deslize a linha volta e pode deslizar de novo`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Pagar o aluguel").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("excluir", "excluir"), calls)
    }

    @Test
    fun `deslizar para o lado desligado nao faz nada`() {
        setRow(withStart = false)

        compose.onNodeWithText("Pagar o aluguel").performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertTrue(calls.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    // --- A2: limiar de 40% da largura ------------------------------------------------------------
    // Deslizes lentos (2 s): abaixo da velocidade de "arremesso", quem decide é só a posição.

    @Test
    fun `deslize lento abaixo de 40 por cento da largura nao dispara`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeLeft(startX = right, endX = right - width * 0.3f, durationMillis = 2_000)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeRight(startX = left, endX = left + width * 0.3f, durationMillis = 2_000)
        }
        compose.waitForIdle()

        assertTrue(calls.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    @Test
    fun `deslize lento acima de 40 por cento da largura dispara`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeLeft(startX = right, endX = right - width * 0.6f, durationMillis = 2_000)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeRight(startX = left, endX = left + width * 0.6f, durationMillis = 2_000)
        }
        compose.waitForIdle()

        assertEquals(listOf("excluir", "concluir"), calls)
    }

    /**
     * Perto do limiar: 30% e 60% passariam também com o limiar em 50% (ou o padrão de 56dp perderia
     * em 30%). Aqui 35% não dispara e 47% dispara (≈45% depois da folga do toque), o que só vale
     * com o limiar de 40%.
     */
    @Test
    fun `deslize lento perto do limiar - 35 por cento volta e 47 por cento dispara`() {
        setRow()

        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeLeft(startX = right, endX = right - width * 0.35f, durationMillis = 2_000)
        }
        compose.waitForIdle()
        assertTrue("35% não dispara: $calls", calls.isEmpty())

        compose.onNodeWithText("Pagar o aluguel").performTouchInput {
            swipeLeft(startX = right, endX = right - width * 0.47f, durationMillis = 2_000)
        }
        compose.waitForIdle()
        assertEquals(listOf("excluir"), calls)
        assertEquals(listOf(Haptics.typeOf(HapticEvent.Delete)), haptics)
    }

    // --- removesRow: excluir com Desfazer (aba Tarefas) ------------------------------------------

    private fun rowLeft(): Float =
        compose.onNodeWithText("Linha", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    @Test
    fun `sem removesRow a linha volta ao lugar depois do deslize`() {
        compose.setContent {
            KalendrysTheme {
                SwipeRow(
                    modifier = Modifier.width(360.dp),
                    endAction = SwipeAction("Excluir", Icons.Outlined.Delete, HapticEvent.Delete) { calls += "excluir" },
                ) { actions -> ListRow(title = "Linha", modifier = actions) }
            }
        }
        val atRest = rowLeft()

        compose.onNodeWithText("Linha").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("excluir"), calls)
        assertEquals(atRest, rowLeft())
    }

    @Test
    fun `com removesRow a linha fica fora ate sair e volta visivel quando o item e devolvido`() {
        val present = mutableStateOf(true)
        compose.setContent {
            KalendrysTheme {
                LazyColumn(modifier = Modifier.width(360.dp)) {
                    if (present.value) {
                        item(key = 1L) {
                            SwipeRow(
                                endAction = SwipeAction(
                                    label = "Excluir",
                                    icon = Icons.Outlined.Delete,
                                    event = HapticEvent.Delete,
                                    removesRow = true,
                                ) { calls += "excluir" },
                            ) { actions -> ListRow(title = "Linha", modifier = actions) }
                        }
                    }
                }
            }
        }
        val atRest = rowLeft()

        compose.onNodeWithText("Linha").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        // Não volta deslizando: fica fora da tela até a lista tirar o item (sem "piscada").
        assertEquals(listOf("excluir"), calls)
        assertTrue(rowLeft() < 0f)

        // A lista atualiza (item some) e o Desfazer o devolve com a mesma key: estado novo, visível.
        compose.runOnIdle { present.value = false }
        compose.waitForIdle()
        compose.runOnIdle { present.value = true }
        compose.waitForIdle()
        assertEquals(atRest, rowLeft())
    }
}
