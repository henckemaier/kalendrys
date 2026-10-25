package com.kalendrys.app.data.settings

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `padrao e resumo ligado as oito`() {
        val settings = AppSettings()
        assertTrue(settings.summaryEnabled)
        assertEquals(LocalTime.of(8, 0), settings.summaryTime)
    }

    @Test
    fun `horario vai e volta como minutos do dia`() {
        listOf(LocalTime.MIDNIGHT, LocalTime.of(7, 30), LocalTime.of(23, 59)).forEach { time ->
            assertEquals(time, AppSettings.timeFromMinuteOfDay(AppSettings.minuteOfDay(time)))
        }
        assertEquals(450, AppSettings.minuteOfDay(LocalTime.of(7, 30)))
    }
}
