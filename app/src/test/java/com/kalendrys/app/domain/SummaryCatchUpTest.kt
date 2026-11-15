package com.kalendrys.app.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plano do catch-up do resumo diário (ADR-0003, 4.3.2 e 4.3.4). */
class SummaryCatchUpTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val day = LocalDate.of(2030, 1, 7)
    private val eight = LocalTime.of(8, 0)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun due(lastSeen: Long, now: Long, enabled: Boolean = true, time: LocalTime = eight) =
        SummaryCatchUp.due(enabled, time, lastSeen, now, zone)

    @Test
    fun `atrasado dentro da tolerancia sai`() {
        assertEquals(at(8), due(lastSeen = at(7, 50), now = at(8, 3)))
    }

    @Test
    fun `limite exato da tolerancia sai e acima e descartado`() {
        assertEquals(at(8), due(at(7, 50), at(9, 0)))
        assertNull(due(at(7, 50), at(9, 0) + 1))
    }

    @Test
    fun `antes do horario nada sai`() {
        assertNull(due(at(7), at(7, 59)))
    }

    @Test
    fun `ja processado nao sai de novo`() {
        assertNull(due(lastSeen = at(8), now = at(8, 5)))
        assertNull(due(lastSeen = at(8, 3), now = at(8, 10)))
    }

    @Test
    fun `desligado nao sai`() {
        assertNull(due(at(7, 50), at(8, 3), enabled = false))
    }

    @Test
    fun `relogio voltou nada sai`() {
        assertNull(due(lastSeen = at(8, 30), now = at(8, 3)))
        assertNull(due(lastSeen = at(8, 3), now = at(8, 3)))
    }

    @Test
    fun `janela cruzando a meia-noite entrega o de ontem`() {
        val late = LocalTime.of(23, 50)
        val yesterday = day.minusDays(1)
        assertEquals(at(23, 50, yesterday), due(at(23, 40, yesterday), at(0, 20), time = late))
    }
}
