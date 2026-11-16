package com.kalendrys.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Aparência no [SettingsRepository] (ADR-0002, 12.3): padrão, gravar e ler, nome desconhecido. */
class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        val file = File(folder.root, "settings-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { file }
        repository = SettingsRepository(dataStore)
    }

    @Test
    fun `padrao e tema do sistema sem cor dinamica`() = runTest {
        val settings = repository.settings.first()

        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.dynamicColor)
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `tema e cor dinamica gravados voltam na leitura`() = runTest {
        ThemeMode.entries.forEach { mode ->
            repository.setThemeMode(mode)
            assertEquals(mode, repository.settings.first().themeMode)
        }

        repository.setDynamicColor(true)
        assertEquals(true, repository.settings.first().dynamicColor)
        repository.setDynamicColor(false)
        assertEquals(false, repository.settings.first().dynamicColor)
    }

    @Test
    fun `tema e guardado pelo nome do enum`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)

        assertEquals("DARK", dataStore.data.first()[stringPreferencesKey("theme_mode")])
    }

    @Test
    fun `nome de tema desconhecido cai em SYSTEM sem mexer no resto`() = runTest {
        repository.setDynamicColor(true)
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "SEPIA" }

        val settings = repository.settings.first()
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(true, settings.dynamicColor)
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("dynamic_color")])
    }

    @Test
    fun `fromNameOrNull so aceita o nome exato`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromNameOrNull("LIGHT"))
        assertNull(ThemeMode.fromNameOrNull("light"))
        assertNull(ThemeMode.fromNameOrNull(""))
        assertNull(ThemeMode.fromNameOrNull(null))
    }
}
