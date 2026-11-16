package com.kalendrys.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.material3.ColorProviders
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.ui.theme.DarkColors
import com.kalendrys.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cores concretas do [WidgetTheme] por variante: as da marca são as do app (as que antes eram
 * fixas em KalendrysWidgets: destaque = primary escuro 0xFFAAC7FF, texto do dia de hoje =
 * onPrimary 0xFF0A305F, atrasada = error 0xFFFFB4AB); a dinâmica só existe no Android 12+.
 * O-4: fundo claro opaco (no máximo 5% de transparência), 75% só no escuro; o círculo do dia de
 * hoje é o primary do mesmo esquema do número.
 */
@RunWith(AndroidJUnit4::class)
class WidgetThemeColorsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    /** Pior papel de parede para o texto da variante: preto atrás do claro, branco atrás do escuro. */
    private fun worstWallpaper(variant: WidgetVariant) = if (variant.isDark) Color.White else Color.Black

    @Test
    @Config(sdk = [34])
    fun `variantes da marca sao os esquemas do app, fundo escuro de 75 e claro opaco`() {
        assertEquals(DarkColors, WidgetTheme.colorScheme(context, WidgetVariant.BRAND_DARK))
        assertEquals(LightColors, WidgetTheme.colorScheme(context, WidgetVariant.BRAND_LIGHT))
        // As cores antes fixas saem do esquema escuro.
        assertEquals(0xFFAAC7FF.toInt(), DarkColors.primary.toArgb())
        assertEquals(0xFF0A305F.toInt(), DarkColors.onPrimary.toArgb())
        assertEquals(0xFFFFB4AB.toInt(), DarkColors.error.toArgb())
        assertEquals(0xBF0C0E13.toInt(), WidgetTheme.background(context, WidgetVariant.BRAND_DARK).toArgb())
        assertEquals(0xFFF9F9FF.toInt(), WidgetTheme.background(context, WidgetVariant.BRAND_LIGHT).toArgb())
    }

    @Test
    @Config(sdk = [34])
    fun `no Android 12 ou mais a variante dinamica usa as cores do sistema, escura a 75 e clara opaca`() {
        val dynamicDark = WidgetTheme.colorScheme(context, WidgetVariant.DYNAMIC_DARK)
        assertNotEquals(DarkColors, dynamicDark)
        assertEquals(
            dynamicDark.surface.copy(alpha = 0.75f),
            WidgetTheme.background(context, WidgetVariant.DYNAMIC_DARK),
        )
        val dynamicLight = WidgetTheme.colorScheme(context, WidgetVariant.DYNAMIC_LIGHT)
        assertNotEquals(LightColors, dynamicLight)
        assertEquals(dynamicLight.surface.copy(alpha = 1f), WidgetTheme.background(context, WidgetVariant.DYNAMIC_LIGHT))
        assertEquals(
            WidgetScheme(WidgetVariant.DYNAMIC_LIGHT, WidgetVariant.DYNAMIC_DARK),
            WidgetTheme.schemeFor(Appearance(ThemeMode.SYSTEM, dynamicColor = true)),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `antes do Android 12 a cor dinamica ligada fica na marca`() {
        assertEquals(
            WidgetScheme(WidgetVariant.BRAND_LIGHT, WidgetVariant.BRAND_DARK),
            WidgetTheme.schemeFor(Appearance(ThemeMode.SYSTEM, dynamicColor = true)),
        )
        assertEquals(DarkColors, WidgetTheme.colorScheme(context, WidgetVariant.DYNAMIC_DARK))
    }

    @Test
    @Config(sdk = [34])
    fun `fundo claro tem no maximo 5 por cento de transparencia, inclusive o dinamico`() {
        listOf(WidgetVariant.BRAND_LIGHT, WidgetVariant.DYNAMIC_LIGHT).forEach { variant ->
            val alpha = WidgetTheme.background(context, variant).alpha
            assertTrue("$variant: alfa $alpha", alpha >= 0.95f)
        }
    }

    @Test
    @Config(sdk = [34])
    fun `texto secundario e principal legiveis sobre o fundo mesmo no pior papel de parede`() {
        WidgetVariant.entries.forEach { variant ->
            val scheme = WidgetTheme.colorScheme(context, variant)
            val seen = WidgetTheme.background(context, variant).compositeOver(worstWallpaper(variant))
            listOf("secundario" to scheme.onSurfaceVariant, "principal" to scheme.onSurface).forEach { (role, text) ->
                val ratio = contrast(text, seen)
                assertTrue("$variant $role: contraste $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    @Config(sdk = [34])
    fun `numero do dia de hoje legivel sobre o circulo em todas as variantes`() {
        WidgetVariant.entries.forEach { variant ->
            val scheme = WidgetTheme.colorScheme(context, variant)
            val ratio = contrast(scheme.onPrimary, scheme.primary)
            assertTrue("$variant: contraste $ratio", ratio >= 4.5f)
        }
    }

    @Test
    @Config(sdk = [34])
    fun `circulo do dia de hoje e o primary do mesmo esquema do numero nas combinacoes de aparencia`() {
        for (mode in ThemeMode.entries) {
            for (dynamic in listOf(false, true)) {
                val scheme = WidgetTheme.schemeFor(Appearance(mode, dynamicColor = dynamic))
                val palette = WidgetPalette.create(context, scheme)
                val label = "$mode dinamica=$dynamic"
                if (scheme.followsSystem && scheme.day.isDynamic) {
                    // Dinâmica seguindo o aparelho: os recursos do sistema, que trocam com o modo dele.
                    assertSame(label, DynamicThemeColorProviders.primary, palette.today)
                    assertSame(label, DynamicThemeColorProviders.onPrimary, palette.onToday)
                } else {
                    val expected = ColorProviders(
                        light = WidgetTheme.colorScheme(context, scheme.day),
                        dark = WidgetTheme.colorScheme(context, scheme.night),
                    )
                    assertEquals(label, expected.primary, palette.today)
                    assertEquals(label, expected.onPrimary, palette.onToday)
                }
            }
        }
    }
}
