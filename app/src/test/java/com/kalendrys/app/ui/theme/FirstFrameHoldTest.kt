package com.kalendrys.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstFrameHoldTest {

    private var clock = 1_000L
    private var ready = false
    private val hold = FirstFrameHold(isReady = { ready }, now = { clock }, timeoutMs = 1_000L)

    @Test
    fun `segura enquanto a aparencia nao saiu e o prazo nao venceu`() {
        clock += 999
        assertEquals(false, hold.canDraw())
    }

    @Test
    fun `solta assim que a composicao sai com a aparencia`() {
        clock += 10
        ready = true
        assertEquals(true, hold.canDraw())
    }

    @Test
    fun `solta em 1 s mesmo sem a aparencia`() {
        clock += 1_000
        assertEquals(true, hold.canDraw())
    }
}
