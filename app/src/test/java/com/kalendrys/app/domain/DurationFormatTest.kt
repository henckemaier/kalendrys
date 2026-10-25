package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `minutos horas cheias e horas com minutos`() {
        assertEquals("15 min", DurationFormat.format(15))
        assertEquals("1h", DurationFormat.format(60))
        assertEquals("1h 20min", DurationFormat.format(80))
        assertEquals("2h 5min", DurationFormat.format(125))
    }
}
