package com.kalendrys.app.widget

import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.widget.WidgetVariant.BRAND_DARK
import com.kalendrys.app.widget.WidgetVariant.BRAND_LIGHT
import com.kalendrys.app.widget.WidgetVariant.DYNAMIC_DARK
import com.kalendrys.app.widget.WidgetVariant.DYNAMIC_LIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Esquema dos widgets por combinação de Aparência (ADR-0002, 12.5), sem Android: Sistema segue o
 * dia/noite de quem desenha; Claro e Escuro forçados usam a MESMA variante de dia e de noite (o
 * launcher não recebe o espelho do modo noturno do app); cor dinâmica só onde existe.
 */
class WidgetSchemeTest {

    private fun scheme(mode: ThemeMode, dynamic: Boolean, available: Boolean = true) =
        WidgetScheme.of(Appearance(themeMode = mode, dynamicColor = dynamic), dynamicAvailable = available)

    @Test
    fun `sistema sem cor dinamica e claro de dia e escuro de noite`() {
        val scheme = scheme(ThemeMode.SYSTEM, dynamic = false)
        assertEquals(WidgetScheme(day = BRAND_LIGHT, night = BRAND_DARK), scheme)
        assertTrue(scheme.followsSystem)
    }

    @Test
    fun `claro e escuro forcados usam a mesma variante nas duas metades`() {
        assertEquals(WidgetScheme(BRAND_LIGHT, BRAND_LIGHT), scheme(ThemeMode.LIGHT, dynamic = false))
        assertEquals(WidgetScheme(BRAND_DARK, BRAND_DARK), scheme(ThemeMode.DARK, dynamic = false))
        assertFalse(scheme(ThemeMode.LIGHT, dynamic = false).followsSystem)
        assertFalse(scheme(ThemeMode.DARK, dynamic = false).followsSystem)
    }

    @Test
    fun `cor dinamica disponivel troca as variantes da marca pelas dinamicas`() {
        assertEquals(WidgetScheme(DYNAMIC_LIGHT, DYNAMIC_DARK), scheme(ThemeMode.SYSTEM, dynamic = true))
        assertEquals(WidgetScheme(DYNAMIC_LIGHT, DYNAMIC_LIGHT), scheme(ThemeMode.LIGHT, dynamic = true))
        assertEquals(WidgetScheme(DYNAMIC_DARK, DYNAMIC_DARK), scheme(ThemeMode.DARK, dynamic = true))
    }

    @Test
    fun `cor dinamica ligada sem suporte do aparelho fica nas cores da marca`() {
        for (mode in ThemeMode.entries) {
            assertEquals(scheme(mode, dynamic = false), scheme(mode, dynamic = true, available = false))
        }
    }

    @Test
    fun `paleta de rotina e a escura no escuro e a clara no claro`() {
        val color = com.kalendrys.app.domain.RoutineColor.GREEN
        val dark = com.kalendrys.app.ui.routines.RoutinePalette.dark(color).accent
        val light = com.kalendrys.app.ui.routines.RoutinePalette.light(color).accent
        assertEquals(dark, WidgetTheme.routineAccent(BRAND_DARK, color))
        assertEquals(dark, WidgetTheme.routineAccent(DYNAMIC_DARK, color))
        assertEquals(light, WidgetTheme.routineAccent(BRAND_LIGHT, color))
        assertEquals(light, WidgetTheme.routineAccent(DYNAMIC_LIGHT, color))
    }
}
