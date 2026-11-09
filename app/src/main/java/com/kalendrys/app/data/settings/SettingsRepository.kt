package com.kalendrys.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
            )
        }
        .distinctUntilChanged()

    suspend fun setSummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[SUMMARY_ENABLED] = enabled }
    }

    suspend fun setSummaryTime(time: LocalTime) {
        dataStore.edit { it[SUMMARY_MINUTE] = AppSettings.minuteOfDay(time) }
    }

    /**
     * Último instante (epoch millis) em que o app processou os lembretes — avança quando um alarme
     * dispara e quando o app vem ao foreground. `null` até a primeira vez. Base da recuperação de
     * alarmes perdidos ([com.kalendrys.app.reminder.MissedReminderRecovery]). Fica fora de
     * [AppSettings] de propósito: é bookkeeping interno, não preferência, e não deve reemitir o
     * fluxo de configurações (que reagenda o resumo diário) a cada atualização.
     */
    suspend fun lastSeenAt(): Long? = dataStore.data.first()[LAST_SEEN]

    suspend fun setLastSeen(nowMillis: Long) {
        dataStore.edit { it[LAST_SEEN] = nowMillis }
    }

    private companion object {
        val SUMMARY_ENABLED = booleanPreferencesKey("summary_enabled")
        val SUMMARY_MINUTE = intPreferencesKey("summary_minute_of_day")
        val LAST_SEEN = longPreferencesKey("last_seen_at")
    }
}
