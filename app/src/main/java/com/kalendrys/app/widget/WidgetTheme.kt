package com.kalendrys.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.ui.routines.RoutinePalette
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.ui.theme.DarkColors
import com.kalendrys.app.ui.theme.LightColors
import com.kalendrys.app.ui.theme.supportsDynamicColor
import androidx.glance.color.ColorProvider as DayNightColorProvider

/** Cores de uma das metades (dia ou noite) do widget. */
enum class WidgetVariant(val isDark: Boolean, val isDynamic: Boolean) {
    BRAND_LIGHT(isDark = false, isDynamic = false),
    BRAND_DARK(isDark = true, isDynamic = false),
    DYNAMIC_LIGHT(isDark = false, isDynamic = true),
    DYNAMIC_DARK(isDark = true, isDynamic = true),
}

/**
 * Esquema dos widgets para uma [Appearance] (ADR-0002, 12.5): que cores o widget usa de dia e de
 * noite. Lógica pura, sem Android, para ser testada por combinação.
 *
 * Quem decide "dia" ou "noite" é quem desenha o widget: o launcher, com o modo do APARELHO. Por isso:
 * - [ThemeMode.SYSTEM]: claro de dia e escuro de noite; o widget acompanha o aparelho sozinho;
 * - [ThemeMode.LIGHT]/[ThemeMode.DARK]: a MESMA variante nas duas metades. O espelho do app
 *   (`setApplicationNightMode`, [com.kalendrys.app.ui.theme.AppNightMode]) vale para as janelas do
 *   app, não para o launcher; com as duas metades iguais, o forçado vale qualquer que seja o modo
 *   de quem desenha.
 * - cor dinâmica ligada e disponível (Android 12+): as variantes dinâmicas; sem suporte, as da marca.
 */
data class WidgetScheme(val day: WidgetVariant, val night: WidgetVariant) {

    /** Claro e escuro conforme o aparelho ([ThemeMode.SYSTEM]). */
    val followsSystem: Boolean get() = day != night

    companion object {
        fun of(appearance: Appearance, dynamicAvailable: Boolean): WidgetScheme {
            val dynamic = appearance.dynamicColor && dynamicAvailable
            val light = if (dynamic) WidgetVariant.DYNAMIC_LIGHT else WidgetVariant.BRAND_LIGHT
            val dark = if (dynamic) WidgetVariant.DYNAMIC_DARK else WidgetVariant.BRAND_DARK
            return when (appearance.themeMode) {
                ThemeMode.SYSTEM -> WidgetScheme(day = light, night = dark)
                ThemeMode.LIGHT -> WidgetScheme(day = light, night = light)
                ThemeMode.DARK -> WidgetScheme(day = dark, night = dark)
            }
        }
    }
}

/**
 * Todas as cores dos 3 widgets (ADR-0002, 12.5), inclusive as que antes eram fixas em
 * `KalendrysWidgets` (fundo, destaque, texto do dia de hoje, atrasada) e a paleta de rotina.
 */
object WidgetTheme {

    /** Opacidade do fundo: o papel de parede aparece sem prejudicar a leitura. */
    private const val BACKGROUND_ALPHA = 0.75f

    /** Fundo escuro de sempre (0xBF0C0E13), um pouco mais escuro que a superfície do app. */
    val BackgroundDark = Color(0xBF0C0E13)

    /** Fundo claro: a superfície clara do app com a mesma opacidade. */
    val BackgroundLight = Color(0xBFF9F9FF)

    /** Destaque da rotina: pastel no escuro, tom mais forte no claro, como no app. */
    fun routineAccent(variant: WidgetVariant, color: RoutineColor): Color =
        if (variant.isDark) RoutinePalette.dark(color).accent else RoutinePalette.light(color).accent

    fun colorScheme(context: Context, variant: WidgetVariant): ColorScheme = when {
        variant.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (variant.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        variant.isDark -> DarkColors
        else -> LightColors
    }

    fun background(context: Context, variant: WidgetVariant): Color = when (variant) {
        WidgetVariant.BRAND_DARK -> BackgroundDark
        WidgetVariant.BRAND_LIGHT -> BackgroundLight
        else -> colorScheme(context, variant).surface.copy(alpha = BACKGROUND_ALPHA)
    }

    /** Esquema da aparência atual, com a disponibilidade de cor dinâmica do aparelho. */
    fun schemeFor(appearance: Appearance): WidgetScheme = WidgetScheme.of(appearance, supportsDynamicColor())
}

/** Cores prontas para a composição de um widget. */
class WidgetPalette(
    val colors: ColorProviders,
    val background: ColorProvider,
    private val scheme: WidgetScheme,
) {
    fun routineAccent(color: RoutineColor): ColorProvider = DayNightColorProvider(
        day = WidgetTheme.routineAccent(scheme.day, color),
        night = WidgetTheme.routineAccent(scheme.night, color),
    )

    companion object {
        fun create(context: Context, scheme: WidgetScheme): WidgetPalette {
            val colors = when {
                // Dinâmica seguindo o aparelho: as cores do sistema, que acompanham o papel de parede.
                scheme.followsSystem && scheme.day.isDynamic -> DynamicThemeColorProviders
                else -> ColorProviders(
                    light = WidgetTheme.colorScheme(context, scheme.day),
                    dark = WidgetTheme.colorScheme(context, scheme.night),
                )
            }
            val background = DayNightColorProvider(
                day = WidgetTheme.background(context, scheme.day),
                night = WidgetTheme.background(context, scheme.night),
            )
            return WidgetPalette(colors, background, scheme)
        }
    }
}

private val LocalWidgetPalette = staticCompositionLocalOf<WidgetPalette> {
    error("KalendrysWidgetTheme ausente")
}

/** Paleta do widget em composição, para o que não sai do `GlanceTheme.colors` (fundo e rotina). */
val widgetPalette: WidgetPalette
    @Composable get() = LocalWidgetPalette.current

/** Tema dos widgets: `GlanceTheme` com as cores do [scheme] mais o fundo e a paleta de rotina. */
@Composable
fun KalendrysWidgetTheme(scheme: WidgetScheme, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val palette = remember(scheme) { WidgetPalette.create(context, scheme) }
    GlanceTheme(colors = palette.colors) {
        CompositionLocalProvider(LocalWidgetPalette provides palette, content = content)
    }
}
