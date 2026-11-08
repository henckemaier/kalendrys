package com.kalendrys.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.repository.BackupManager
import com.kalendrys.app.data.repository.HistoryExporter
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.update.InstallOutcome
import com.kalendrys.app.update.UpdateCheck
import com.kalendrys.app.update.UpdateChecker
import com.kalendrys.app.update.UpdateInstaller
import org.json.JSONException
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Os alarmes se reajustam sozinhos: o KalendrysApp observa as configurações. */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val historyExporter: HistoryExporter,
    private val backupManager: BackupManager,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : ViewModel() {

    /** Etapas da exportação do histórico que a tela precisa mostrar. */
    sealed interface ExportEvent {
        /** Abrir o seletor de arquivo com este nome sugerido. */
        data class ChooseFile(val suggestedName: String) : ExportEvent
        data class Exported(val count: Int) : ExportEvent
        data object NothingToExport : ExportEvent
        data object Failed : ExportEvent
    }

    /** Etapas do backup/restauro completo que a tela precisa mostrar. */
    sealed interface BackupEvent {
        /** Abrir o seletor para CRIAR o arquivo de backup com este nome sugerido. */
        data class ChooseExportFile(val suggestedName: String) : BackupEvent
        data class Exported(val stats: BackupManager.Stats) : BackupEvent
        data class Restored(val stats: BackupManager.Stats) : BackupEvent
        data object ExportFailed : BackupEvent
        data object RestoreFailed : BackupEvent
    }

    /** Estado do atualizador in-app mostrado em Configurações > Sobre. */
    sealed interface UpdateUi {
        data object Idle : UpdateUi
        data object Checking : UpdateUi
        data object UpToDate : UpdateUi
        data class Available(val versionName: String, val apkUrl: String) : UpdateUi
        data class Downloading(val percent: Int) : UpdateUi
        /** APK entregue ao instalador do sistema; falta o usuário confirmar no Android. */
        data object AwaitingInstall : UpdateUi
        /** Precisa do consentimento "instalar apps desconhecidos". */
        data object NeedsPermission : UpdateUi
        data object Failed : UpdateUi
    }

    /** null até a primeira leitura do DataStore. */
    val settings: StateFlow<AppSettings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
    val export: Flow<ExportEvent> = exportEvents.receiveAsFlow()

    private val backupEvents = Channel<BackupEvent>(Channel.BUFFERED)
    val backup: Flow<BackupEvent> = backupEvents.receiveAsFlow()

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

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

    /** Backup completo é sempre útil para troca de aparelho: abre direto o seletor para criar o arquivo. */
    fun requestBackupExport() {
        viewModelScope.launch {
            backupEvents.send(BackupEvent.ChooseExportFile("kalendrys-backup-${LocalDate.now()}.json"))
        }
    }

    fun exportBackupTo(uri: Uri) {
        viewModelScope.launch {
            val event = try {
                BackupEvent.Exported(backupManager.exportTo(uri))
            } catch (e: IOException) {
                BackupEvent.ExportFailed
            } catch (e: SecurityException) {
                BackupEvent.ExportFailed
            }
            backupEvents.send(event)
        }
    }

    /** Destrutivo: a tela só chama aqui depois da confirmação do usuário. */
    fun restoreBackupFrom(uri: Uri) {
        viewModelScope.launch {
            val event = try {
                BackupEvent.Restored(backupManager.importFrom(uri))
            } catch (e: IOException) {
                BackupEvent.RestoreFailed
            } catch (e: JSONException) {
                BackupEvent.RestoreFailed
            } catch (e: SecurityException) {
                BackupEvent.RestoreFailed
            }
            backupEvents.send(event)
        }
    }

    /** Só no toque do botão (sem auto-check). Ignora toques enquanto já está checando/baixando. */
    fun checkForUpdates() {
        if (_update.value == UpdateUi.Checking || _update.value is UpdateUi.Downloading) return
        _update.value = UpdateUi.Checking
        viewModelScope.launch {
            _update.value = when (val result = updateChecker.check()) {
                UpdateCheck.UpToDate -> UpdateUi.UpToDate
                is UpdateCheck.Available -> UpdateUi.Available(result.versionName, result.apkUrl)
                UpdateCheck.Failed -> UpdateUi.Failed
            }
        }
    }

    fun downloadUpdate(apkUrl: String) {
        if (_update.value is UpdateUi.Downloading) return
        _update.value = UpdateUi.Downloading(0)
        viewModelScope.launch {
            val outcome = updateInstaller.downloadAndInstall(apkUrl) { percent ->
                _update.value = UpdateUi.Downloading(percent)
            }
            _update.value = when (outcome) {
                InstallOutcome.HandedToSystem -> UpdateUi.AwaitingInstall
                InstallOutcome.NeedsPermission -> UpdateUi.NeedsPermission
                InstallOutcome.Failed -> UpdateUi.Failed
            }
        }
    }

    /** Leva o usuário à tela do sistema para autorizar instalar apps desconhecidos. */
    fun grantInstallPermission() {
        updateInstaller.requestInstallPermission()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                SettingsViewModel(
                    app.settingsRepository,
                    app.historyExporter,
                    app.backupManager,
                    app.updateChecker,
                    app.updateInstaller,
                )
            }
        }
    }
}
