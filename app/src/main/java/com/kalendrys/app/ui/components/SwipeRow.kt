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
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
 */
@Immutable
class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val event: HapticEvent,
    val onAction: () -> Unit,
)

/**
 * Linha com deslize (ADR-0002, 4.2 e 12.4), na API de deslizar da material3 1.4
 * (`SwipeToDismissBox` com `onDismiss`): deslizar para o fim (para a direita, em LTR) dispara
 * [startAction] (concluir/marcar); para o início (para a esquerda) dispara [endAction] (excluir).
 * `null` desliga aquele lado.
 *
 * Sem estado além do do gesto: ao disparar, a linha volta à posição; sumir, riscar ou mostrar o
 * Desfazer é com quem chama. Cada ação tem a mesma [CustomAccessibilityAction], com o rótulo dado.
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
    val state = rememberSwipeToDismissBoxState()
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
            // A linha volta ao lugar; se o item sumir da lista, a composição dele acaba junto.
            scope.launch { state.reset() }
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
