package com.kalendrys.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        // Arquivo ilegível: segue com os padrões em vez de derrubar o app.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            AppSettings(
                summaryEnabled = prefs[SUMMARY_ENABLED] ?: true,
                summaryTime = prefs[SUMMARY_MINUTE]?.let(AppSettings::timeFromMinuteOfDay)
                    ?: AppSettings.DEFAULT_SUMMARY_TIME,
                // Nome desconhecido (ex.: valor de uma versão futura) cai em SYSTEM.
                themeMode = ThemeMode.fromNameOrNull(prefs[THEME_MODE]) ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[DYNAMIC_COLOR] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setSummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[SUMMARY_ENABLED] = enabled }
    }

    suspend fun setSummaryTime(time: LocalTime) {
        dataStore.edit { it[SUMMARY_MINUTE] = AppSettings.minuteOfDay(time) }
    }

    /** Guardado pelo nome do enum ([ThemeMode.name]). */
    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    /**
     * Último instante (epoch millis) até o qual os lembretes de tarefa foram entregues ou contados.
     * Só o catch-up ([com.kalendrys.app.reminder.ReminderCatchUpRunner], ADR-0003) a grava. `null` até
     * a primeira vez. Fica fora de [AppSettings] de propósito: é bookkeeping interno, não preferência,
     * e não deve reemitir o fluxo de configurações (que reagenda o resumo diário) a cada atualização.
     */
    suspend fun lastSeenAt(): Long? = dataStore.data.first()[LAST_SEEN]

    suspend fun setLastSeen(nowMillis: Long) {
        dataStore.edit { it[LAST_SEEN] = nowMillis }
    }

    /**
     * Marca do catch-up dos avisos de bloco de rotina (ADR-0003, 4.3.1): até aqui os inícios de
     * bloco foram avisados ou descartados. Só o catch-up de rotinas a grava. Fora do backup.
     */
    suspend fun routineLastSeenAt(): Long? = dataStore.data.first()[ROUTINE_LAST_SEEN]

    suspend fun setRoutineLastSeen(nowMillis: Long) {
        dataStore.edit { it[ROUTINE_LAST_SEEN] = nowMillis }
    }

    /**
     * Marca do catch-up do resumo diário (ADR-0003, 4.3.2): até aqui o resumo foi entregue ou
     * descartado. Só o catch-up do resumo a grava. Fora do backup.
     */
    suspend fun summaryLastSeenAt(): Long? = dataStore.data.first()[SUMMARY_LAST_SEEN]

    suspend fun setSummaryLastSeen(nowMillis: Long) {
        dataStore.edit { it[SUMMARY_LAST_SEEN] = nowMillis }
    }

    private companion object {
        val SUMMARY_ENABLED = booleanPreferencesKey("summary_enabled")
        val SUMMARY_MINUTE = intPreferencesKey("summary_minute_of_day")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LAST_SEEN = longPreferencesKey("last_seen_at")
        val ROUTINE_LAST_SEEN = longPreferencesKey("routine_last_seen_at")
        val SUMMARY_LAST_SEEN = longPreferencesKey("summary_last_seen_at")
    }
}
