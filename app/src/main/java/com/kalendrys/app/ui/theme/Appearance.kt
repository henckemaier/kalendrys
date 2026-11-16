package com.kalendrys.app.ui.theme

import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.ThemeMode

/**
 * A parte das configurações que decide o visual (ADR-0002, 12.3). O [com.kalendrys.app.KalendrysApp]
 * a expõe num StateFlow carregado no início do processo; a MainActivity segura o primeiro quadro até
 * ela chegar, para o app nunca abrir num tema e trocar para outro.
 */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
)

fun AppSettings.appearance(): Appearance = Appearance(themeMode, dynamicColor)

/** Escuro ou claro: forçado pela escolha do usuário, ou o do aparelho em [ThemeMode.SYSTEM]. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
