package com.kalendrys.app.ui.theme

import android.app.UiModeManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kalendrys.app.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Espelho do tema no modo noturno do app (parecer do Esquadro sobre a 12.3): só no Android 12+. */
@RunWith(AndroidJUnit4::class)
class AppNightModeTest {

    private val calls = mutableListOf<Int>()
    private val nightMode = AppNightMode(ApplicationProvider.getApplicationContext()) { calls += it }

    @Test
    @Config(sdk = [34])
    fun `no Android 12+ cada tema vira o modo noturno do app`() {
        nightMode.apply(ThemeMode.DARK)
        nightMode.apply(ThemeMode.LIGHT)
        nightMode.apply(ThemeMode.SYSTEM)

        assertEquals(
            listOf(UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_AUTO),
            calls,
        )
    }

    @Test
    @Config(sdk = [34])
    fun `o mesmo tema duas vezes chama o sistema uma vez so, sem recriar a tela em laco`() {
        nightMode.apply(ThemeMode.DARK)
        nightMode.apply(ThemeMode.DARK)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_YES), calls)
    }

    @Test
    @Config(sdk = [31])
    fun `a partir do Android 12 o espelho vale`() {
        nightMode.apply(ThemeMode.LIGHT)

        assertEquals(listOf(UiModeManager.MODE_NIGHT_NO), calls)
    }

    @Test
    @Config(sdk = [30])
    fun `antes do Android 12 nada e chamado`() {
        nightMode.apply(ThemeMode.DARK)
        nightMode.apply(ThemeMode.SYSTEM)

        assertEquals(emptyList<Int>(), calls)
    }

    @Test
    @Config(sdk = [26])
    fun `no minSdk tambem nada e chamado`() {
        nightMode.apply(ThemeMode.LIGHT)

        assertEquals(emptyList<Int>(), calls)
    }

    @Test
    @Config(sdk = [34])
    fun `com o UiModeManager de verdade a chamada nao falha`() {
        AppNightMode(ApplicationProvider.getApplicationContext()).apply(ThemeMode.DARK)
    }
}
