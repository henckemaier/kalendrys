package com.kalendrys.app.widget

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.ui.theme.DarkColors
import com.kalendrys.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cores concretas do [WidgetTheme] por variante: as da marca são as do app (as que antes eram
 * fixas em KalendrysWidgets: destaque = primary escuro 0xFFAAC7FF, texto do dia de hoje =
 * onPrimary 0xFF0A305F, atrasada = error 0xFFFFB4AB); a dinâmica só existe no Android 12+.
 */
@RunWith(AndroidJUnit4::class)
class WidgetThemeColorsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(sdk = [34])
    fun `variantes da marca sao os esquemas do app e o fundo de 75 por cento`() {
        assertEquals(DarkColors, WidgetTheme.colorScheme(context, WidgetVariant.BRAND_DARK))
        assertEquals(LightColors, WidgetTheme.colorScheme(context, WidgetVariant.BRAND_LIGHT))
        // As cores antes fixas saem do esquema escuro.
        assertEquals(0xFFAAC7FF.toInt(), DarkColors.primary.toArgb())
        assertEquals(0xFF0A305F.toInt(), DarkColors.onPrimary.toArgb())
        assertEquals(0xFFFFB4AB.toInt(), DarkColors.error.toArgb())
        assertEquals(0xBF0C0E13.toInt(), WidgetTheme.background(context, WidgetVariant.BRAND_DARK).toArgb())
        assertEquals(0xBFF9F9FF.toInt(), WidgetTheme.background(context, WidgetVariant.BRAND_LIGHT).toArgb())
    }

    @Test
    @Config(sdk = [34])
    fun `no Android 12 ou mais a variante dinamica usa as cores do sistema com fundo de 75 por cento`() {
        val dynamicDark = WidgetTheme.colorScheme(context, WidgetVariant.DYNAMIC_DARK)
        assertNotEquals(DarkColors, dynamicDark)
        assertEquals(
            dynamicDark.surface.copy(alpha = 0.75f),
            WidgetTheme.background(context, WidgetVariant.DYNAMIC_DARK),
        )
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
}
