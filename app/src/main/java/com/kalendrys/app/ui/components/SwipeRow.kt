package com.kalendrys.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.kalendrys.app.ui.theme.KalendrysTheme
import com.kalendrys.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Uma ação de deslize do [SwipeRow].
 *
 * @param label rótulo pt-BR da ação personalizada de acessibilidade (ex.: "Concluir", "Excluir").
 * @param event o que a ação faz: define o retorno tátil e a cor do fundo ([HapticEvent.Delete] sai
 *   na cor de erro).
 * @param removesRow `true` quando a ação tira o item da lista (ex.: excluir com Desfazer): depois do
 *   deslize a linha fica fora da tela até sair da composição, em vez de voltar deslizando e só
 *   então sumir quando a lista atualizar. `false` (padrão): a linha volta ao lugar.
 */
@Immutable
class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val event: HapticEvent,
    val removesRow: Boolean = false,
    val onAction: () -> Unit,
)

/** Fração da largura da linha que o deslize precisa passar para disparar a ação (A2). */
const val SWIPE_ROW_THRESHOLD_FRACTION = 0.4f

/**
 * Linha com deslize (ADR-0002, 4.2 e 12.4), na API de deslizar da material3 1.4
 * (`SwipeToDismissBox` com `onDismiss`): deslizar para o fim (para a direita, em LTR) dispara
 * [startAction] (concluir/marcar); para o início (para a esquerda) dispara [endAction] (excluir).
 * `null` desliga aquele lado. O deslize só dispara depois de [SWIPE_ROW_THRESHOLD_FRACTION] (40%)
 * da largura da linha, como na lista de tarefas; um deslize mais curto volta sem fazer nada.
 *
 * Sem estado além do do gesto: ao disparar, a linha volta à posição (ou fica fora, com
 * [SwipeAction.removesRow]); sumir, riscar ou mostrar o Desfazer é com quem chama. O estado do gesto
 * usa `remember`, não `rememberSaveable`: numa `LazyColumn`, o Desfazer devolve o item com a mesma
 * key e um estado salvo o traria de volta ainda "dispensado" (invisível). Cada ação tem a mesma [CustomAccessibilityAction], com o rótulo dado.
 *
 * Retorno tátil: no deslize, o próprio SwipeRow toca o [HapticEvent] da ação (via [Haptics]) antes
 * de chamar [SwipeAction.onAction]. O chamador NÃO repete o retorno tátil no `onAction`, senão
 * vibra duas vezes. Pela ação de acessibilidade não há retorno tátil (o leitor de tela já anuncia).
 *
 * O [content] recebe o `Modifier` com essas ações: aplique-o no nó que mescla a semântica da linha
 * (o `modifier` da [ListRow]), para o leitor de tela oferecer as ações no item que ele foca.
 */
@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    startAction: SwipeAction? = null,
    endAction: SwipeAction? = null,
    shape: Shape = RectangleShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable (actionsModifier: Modifier) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val state = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { totalDistance -> totalDistance * SWIPE_ROW_THRESHOLD_FRACTION },
        )
    }
    val currentStart by rememberUpdatedState(startAction)
    val currentEnd by rememberUpdatedState(endAction)

    val actionsModifier = Modifier.semantics {
        customActions = listOfNotNull(startAction, endAction).map { action ->
            CustomAccessibilityAction(action.label) {
                action.onAction()
                true
            }
        }
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = startAction != null,
        enableDismissFromEndToStart = endAction != null,
        onDismiss = { direction ->
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> currentStart
                SwipeToDismissBoxValue.EndToStart -> currentEnd
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                haptics.perform(action.event)
                action.onAction()
            }
            // A linha volta ao lugar, salvo se a ação tira o item da lista: aí fica fora até sair.
            if (action?.removesRow != true) scope.launch { state.reset() }
        },
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> null
            }
            SwipeBackground(
                action = action,
                alignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
                shape = shape,
            )
        },
    ) {
        Box(modifier = Modifier.clip(shape).background(containerColor)) {
            content(actionsModifier)
        }
    }
}

@Composable
private fun SwipeBackground(action: SwipeAction?, alignment: Alignment, shape: Shape) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (action?.event) {
        null -> Color.Transparent to Color.Transparent
        HapticEvent.Delete -> colors.errorContainer to colors.onErrorContainer
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(background)
            .padding(horizontal = Spacing.xl),
        contentAlignment = alignment,
    ) {
        if (action != null) {
            Icon(imageVector = action.icon, contentDescription = null, tint = foreground)
        }
    }
}

@KalendrysPreviews
@Composable
private fun SwipeRowPreview() {
    KalendrysTheme {
        Surface {
            Column {
                SwipeRow(
                    startAction = SwipeAction("Concluir", Icons.Outlined.CheckCircle, HapticEvent.Complete) {},
                    endAction = SwipeAction("Excluir", Icons.Outlined.Delete, HapticEvent.Delete) {},
                ) { actions ->
                    ListRow(title = "Pagar o aluguel", supporting = "Hoje, 9:00", modifier = actions, onClick = {})
                }
                SwipeRow(
                    shape = MaterialTheme.shapes.medium,
                    endAction = SwipeAction("Excluir", Icons.Outlined.Delete, HapticEvent.Delete) {},
                ) { actions ->
                    ListRow(title = "Só excluir, com cantos", modifier = actions)
                }
            }
        }
    }
}
