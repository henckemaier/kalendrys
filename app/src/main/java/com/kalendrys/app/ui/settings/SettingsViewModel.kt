package com.kalendrys.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.repository.HistoryExporter
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Os alarmes se reajustam sozinhos: o KalendrysApp observa as configurações. */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val historyExporter: HistoryExporter,
) : ViewModel() {

    /** Etapas da exportação do histórico que a tela precisa mostrar. */
    sealed interface ExportEvent {
        /** Abrir o seletor de arquivo com este nome sugerido. */
        data class ChooseFile(val suggestedName: String) : ExportEvent
        data class Exported(val count: Int) : ExportEvent
        data object NothingToExport : ExportEvent
        data object Failed : ExportEvent
    }

    /** null até a primeira leitura do DataStore. */
    val settings: StateFlow<AppSettings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
    val export: Flow<ExportEvent> = exportEvents.receiveAsFlow()

    fun setSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSummaryEnabled(enabled) }
    }

    fun setSummaryTime(time: LocalTime) {
        viewModelScope.launch { repository.setSummaryTime(time) }
    }

    /** Sem nenhuma conclusão não há o que salvar: avisa em vez de abrir o seletor de arquivo. */
    fun requestExport() {
        viewModelScope.launch {
            val event = if (historyExporter.count() == 0) {
                ExportEvent.NothingToExport
            } else {
                ExportEvent.ChooseFile("kalendrys-historico-${LocalDate.now()}.csv")
            }
            exportEvents.send(event)
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val event = try {
                ExportEvent.Exported(historyExporter.exportTo(uri))
            } catch (e: IOException) {
                ExportEvent.Failed
            } catch (e: SecurityException) {
                ExportEvent.Failed
            }
            exportEvents.send(event)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                SettingsViewModel(app.settingsRepository, app.historyExporter)
            }
        }
    }
}
