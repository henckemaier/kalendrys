package com.kalendrys.app.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailySummaryScheduleTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val time = LocalTime.of(21, 30)

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `desligado nao tem proximo resumo`() {
        assertNull(DailySummarySchedule.nextAt(false, time, LocalDateTime.of(2026, 11, 16, 8, 0), zone))
    }

    @Test
    fun `antes do horario e hoje, no horario ou depois e amanha`() {
        assertEquals(
            millis(LocalDateTime.of(2026, 11, 16, 21, 30)),
            DailySummarySchedule.nextAt(true, time, LocalDateTime.of(2026, 11, 16, 8, 0), zone),
        )
        assertEquals(
            millis(LocalDateTime.of(2026, 11, 17, 21, 30)),
            DailySummarySchedule.nextAt(true, time, LocalDateTime.of(2026, 11, 16, 21, 30), zone),
        )
    }
}
