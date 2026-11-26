package com.kalendrys.app.domain

import com.kalendrys.app.data.local.entity.ExerciseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A regra única de gravação de um exercício (v1.10.0, T2): criação da rotina, edição e salvos. */
class ExerciseFieldsTest {

    @Test
    fun `normalizar apara o nome, prende series e repeticoes em 1 e limpa carga e descanso invalidos`() {
        val fields = ExerciseFields(name = "  Supino  ", sets = 0, reps = -3, load = "   ", restSeconds = -1).normalized()

        assertEquals("Supino", fields.name)
        assertEquals(1, fields.sets)
        assertEquals(1, fields.reps)
        assertNull(fields.load)
        assertNull(fields.restSeconds)
    }

    @Test
    fun `normalizar mantem os valores validos`() {
        val fields = ExerciseFields(name = "Remada", sets = 4, reps = 12, load = " 30kg ", restSeconds = 0).normalized()

        assertEquals(ExerciseFields("Remada", 4, 12, "30kg", 0), fields)
    }

    @Test
    fun `sem nome nao vale`() {
        assertFalse(ExerciseFields(name = "   ", sets = 3, reps = 10).isValid)
        assertTrue(ExerciseFields(name = "Prancha", sets = 3, reps = 10).isValid)
    }

    @Test
    fun `vira exercicio da rotina normalizado e volta pelos mesmos campos`() {
        val item = ExerciseFields(" Agachamento ", 3, 10, "20kg", 60).toItem(routineId = 7, position = 2, createdAt = 99L)

        assertEquals(ExerciseItem(routineId = 7, name = "Agachamento", sets = 3, reps = 10, load = "20kg", restSeconds = 60, position = 2, createdAt = 99L), item)
        assertEquals(ExerciseFields("Agachamento", 3, 10, "20kg", 60), ExerciseFields.of(item))
    }
}
