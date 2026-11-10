package com.kalendrys.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseFormatTest {

    @Test
    fun `series x reps usa o separador de multiplicacao`() {
        assertEquals("3×10", ExerciseFormat.setsReps(3, 10))
        assertEquals("1×1", ExerciseFormat.setsReps(1, 1))
    }

    @Test
    fun `descanso abaixo de um minuto em segundos`() {
        assertEquals("45s", ExerciseFormat.restLabel(45))
        assertEquals("0s", ExerciseFormat.restLabel(0))
    }

    @Test
    fun `descanso multiplo de minuto sem segundos`() {
        assertEquals("1min", ExerciseFormat.restLabel(60))
        assertEquals("2min", ExerciseFormat.restLabel(120))
    }

    @Test
    fun `descanso com minutos e segundos`() {
        assertEquals("1m30s", ExerciseFormat.restLabel(90))
        assertEquals("2m5s", ExerciseFormat.restLabel(125))
    }
}
