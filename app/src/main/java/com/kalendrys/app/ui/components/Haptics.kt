package com.kalendrys.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** O que acabou de acontecer, para o retorno tátil (ADR-0002, 4.2). */
enum class HapticEvent {
    /** Tarefa ou bloco concluído. */
    Complete,

    /** Item marcado (ex.: item de lista pego). */
    Mark,

    /** Item desmarcado. */
    Unmark,

    /** Algo excluído ou removido. */
    Delete,
}

/**
 * Retorno tátil do app, pelo [LocalHapticFeedback] do Compose: não pede permissão (VIBRATE) e
 * respeita a opção do sistema de desligar a vibração ao toque. Sem estado; obtenha com
 * [rememberHaptics].
 */
@Stable
class Haptics(private val feedback: HapticFeedback) {

    fun perform(event: HapticEvent) {
        feedback.performHapticFeedback(typeOf(event))
    }

    fun complete() = perform(HapticEvent.Complete)

    fun mark(marked: Boolean) = perform(if (marked) HapticEvent.Mark else HapticEvent.Unmark)

    fun delete() = perform(HapticEvent.Delete)

    companion object {
        /** Tipo do Compose para cada evento. Exposto para os testes. */
        fun typeOf(event: HapticEvent): HapticFeedbackType = when (event) {
            HapticEvent.Complete -> HapticFeedbackType.Confirm
            HapticEvent.Mark -> HapticFeedbackType.ToggleOn
            HapticEvent.Unmark -> HapticFeedbackType.ToggleOff
            // Mais forte que o toque comum: a exclusão é a ação de maior peso da linha.
            HapticEvent.Delete -> HapticFeedbackType.LongPress
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
