package com.kalendrys.app.ui.theme

import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {

    @Test
    fun `forcado ignora o aparelho e sistema o segue`() {
        assertEquals(false, ThemeMode.LIGHT.isDark(systemDark = true))
        assertEquals(true, ThemeMode.DARK.isDark(systemDark = false))
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `aparencia sai das configuracoes e o padrao e sistema sem cor dinamica`() {
        assertEquals(Appearance(ThemeMode.SYSTEM, dynamicColor = false), AppSettings().appearance())
        assertEquals(
            Appearance(ThemeMode.DARK, dynamicColor = true),
            AppSettings(themeMode = ThemeMode.DARK, dynamicColor = true).appearance(),
        )
    }
}
