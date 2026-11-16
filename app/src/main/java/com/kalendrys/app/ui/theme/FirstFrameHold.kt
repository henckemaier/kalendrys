package com.kalendrys.app.ui.theme

/**
 * Decide se o primeiro quadro já pode ser desenhado (ADR-0002, 12.3): quando a composição saiu com a
 * aparência lida, ou passado o teto ([timeoutMs], NB-3 do Esquadro), para o app nunca ficar parado
 * na janela inicial se o DataStore travar. Sem Android: o relógio vem de fora, para o teste.
 */
class FirstFrameHold(
    private val isReady: () -> Boolean,
    private val now: () -> Long,
    timeoutMs: Long,
) {
    private val deadline = now() + timeoutMs

    fun canDraw(): Boolean = isReady() || now() >= deadline
}
