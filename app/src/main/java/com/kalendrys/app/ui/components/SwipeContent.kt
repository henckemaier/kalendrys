package com.kalendrys.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Conteúdo paginado por deslize horizontal (mês do calendário, semana das rotinas): arrastar para
 * a direita volta, para a esquerda avança, e a troca anima para o lado certo.
 */
@Composable
fun <T : Comparable<T>> HorizontalSwipeContent(
    target: T,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    var dragTotal by remember { mutableFloatStateOf(0f) }

    AnimatedContent(
        targetState = target,
        transitionSpec = {
            val forward = targetState > initialState
            (slideInHorizontally { width -> if (forward) width else -width } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (forward) -width else width } + fadeOut())
        },
        label = label,
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                val threshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragTotal > threshold -> currentOnPrevious()
                            dragTotal < -threshold -> currentOnNext()
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                )
            },
    ) { value ->
        content(value)
    }
}
