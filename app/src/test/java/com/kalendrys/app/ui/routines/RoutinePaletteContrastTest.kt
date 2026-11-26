package com.kalendrys.app.ui.routines

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kalendrys.app.domain.RoutineColor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O ícone sobre a pílula do bloco precisa ser legível nas duas variantes da paleta (ADR-0002, 12.3).
 * Contraste mínimo de 4.5:1 (WCAG AA para texto). A conferência contra a cor dinâmica de verdade
 * (dois papéis de parede) fica na matriz manual da E7.
 *
 * v1.10.0 (N2 da Coruja na T3): o losango da tarefa aninhada sobre a faixa do bloco usa o MESMO onFill,
 * então este teste também prende o contraste dele (pedido: 3:1 para elemento gráfico; aqui 4.5:1).
 */
class RoutinePaletteContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `icone sobre a pilula tem contraste em claro e escuro`() {
        RoutineColor.entries.forEach { color ->
            val variants = listOf("claro" to RoutinePalette.light(color), "escuro" to RoutinePalette.dark(color))
            variants.forEach { (variant, tone) ->
                val ratio = contrast(tone.fill, tone.onFill)
                assertTrue("$color $variant: contraste $ratio", ratio >= 4.5f)
            }
        }
    }
}
