package com.kalendrys.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.ui.routines.RoutinePalette
import com.kalendrys.app.ui.routines.RoutineTone
import com.kalendrys.app.ui.routines.routineTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * As 4 combinações de tema e cor (ADR-0002, 12.3) com o aparelho em tema CLARO (padrão do
 * Robolectric): o tema escolhido manda, não o do aparelho.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KalendrysThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private class Seen(val scheme: ColorScheme, val dark: Boolean, val tone: RoutineTone)

    private fun render(themeMode: ThemeMode, dynamicColor: Boolean): Seen {
        var seen: Seen? = null
        compose.setContent {
            KalendrysTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                seen = Seen(MaterialTheme.colorScheme, LocalDarkTheme.current, routineTone(RoutineColor.BLUE))
            }
        }
        compose.waitForIdle()
        return checkNotNull(seen)
    }

    @Test
    fun `claro com as cores da marca`() {
        val seen = render(ThemeMode.LIGHT, dynamicColor = false)

        assertEquals(LightColors, seen.scheme)
        assertEquals(false, seen.dark)
        assertEquals(RoutinePalette.light(RoutineColor.BLUE), seen.tone)
    }

    @Test
    fun `escuro forcado com o aparelho claro`() {
        val seen = render(ThemeMode.DARK, dynamicColor = false)

        assertEquals(DarkColors, seen.scheme)
        assertEquals(true, seen.dark)
        // A paleta das rotinas segue o tema do app, não o aparelho (antes lia isSystemInDarkTheme).
        assertEquals(RoutinePalette.dark(RoutineColor.BLUE), seen.tone)
    }

    @Test
    fun `claro com cor dinamica`() {
        val seen = render(ThemeMode.LIGHT, dynamicColor = true)

        assertNotEquals(LightColors, seen.scheme)
        assertTrue(seen.scheme.surface.luminance() > 0.5f)
        assertEquals(false, seen.dark)
    }

    @Test
    fun `escuro com cor dinamica`() {
        val seen = render(ThemeMode.DARK, dynamicColor = true)

        assertNotEquals(DarkColors, seen.scheme)
        assertTrue(seen.scheme.surface.luminance() < 0.5f)
        assertEquals(true, seen.dark)
        assertEquals(RoutinePalette.dark(RoutineColor.BLUE), seen.tone)
    }

    @Test
    fun `sistema segue o aparelho claro`() {
        val seen = render(ThemeMode.SYSTEM, dynamicColor = false)

        assertEquals(LightColors, seen.scheme)
        assertEquals(false, seen.dark)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `sistema segue o aparelho escuro`() {
        val seen = render(ThemeMode.SYSTEM, dynamicColor = false)

        assertEquals(DarkColors, seen.scheme)
        assertEquals(true, seen.dark)
    }

    @Test
    @Config(sdk = [34], qualifiers = "night")
    fun `claro forcado com o aparelho escuro`() {
        val seen = render(ThemeMode.LIGHT, dynamicColor = false)

        assertEquals(LightColors, seen.scheme)
        assertEquals(RoutinePalette.light(RoutineColor.BLUE), seen.tone)
    }

    @Test
    @Config(sdk = [30])
    fun `sem cor dinamica no aparelho (Android 11) ficam as cores da marca`() {
        val seen = render(ThemeMode.DARK, dynamicColor = true)

        assertEquals(DarkColors, seen.scheme)
    }

    @Test
    fun `formas do tema`() {
        var shapes: Shapes? = null
        compose.setContent { KalendrysTheme { shapes = MaterialTheme.shapes } }
        compose.waitForIdle()

        assertEquals(KalendrysShapes, shapes)
    }
}
