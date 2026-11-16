package com.kalendrys.app

import com.kalendrys.app.data.settings.ThemeMode
import com.kalendrys.app.ui.theme.Appearance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coletor da Aparência do KalendrysApp (ADR-0002, 12.5): publica toda emissão distinta, inclusive a
 * primeira do processo, e redesenha os widgets só quando a Aparência muda depois dela.
 */
class AppearanceSyncTest {

    private val system = Appearance(ThemeMode.SYSTEM, dynamicColor = false)
    private val dark = Appearance(ThemeMode.DARK, dynamicColor = false)
    private val darkDynamic = Appearance(ThemeMode.DARK, dynamicColor = true)

    private suspend fun run(vararg emissions: Appearance): Pair<List<Appearance>, List<Appearance>> {
        val published = mutableListOf<Appearance>()
        val redrawn = mutableListOf<Appearance>()
        flowOf(*emissions).collectAppearance(onEach = { published += it }, onChanged = { redrawn += it })
        return published to redrawn
    }

    @Test
    fun `primeira emissao do processo publica sem redesenhar os widgets`() = runTest {
        val (published, redrawn) = run(system)
        assertEquals(listOf(system), published)
        assertEquals(emptyList<Appearance>(), redrawn)
    }

    @Test
    fun `cada mudanca depois da primeira redesenha uma vez, repeticao nao`() = runTest {
        val (published, redrawn) = run(system, system, dark, dark, darkDynamic, system)
        assertEquals(listOf(system, dark, darkDynamic, system), published)
        assertEquals(listOf(dark, darkDynamic, system), redrawn)
    }
}
