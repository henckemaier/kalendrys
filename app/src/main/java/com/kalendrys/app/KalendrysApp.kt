package com.kalendrys.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.HistoryExporter
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.data.settings.settingsDataStore
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.reminder.RoutineNotifications
import com.kalendrys.app.summary.DailyScheduler
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Injeção manual de dependências: tudo criado sob demanda e compartilhado
 * via `context.applicationContext as KalendrysApp`.
 */
class KalendrysApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val repository: TaskRepository by lazy {
        TaskRepository(database.taskDao(), database.completionLogDao(), ReminderScheduler(this))
    }

    val routineNotifications: RoutineNotifications by lazy { RoutineNotifications(this) }

    val routineRepository: RoutineRepository by lazy {
        RoutineRepository(
            database.routineDao(),
            database.routineCompletionDao(),
            RoutineAlarms(this),
            routineNotifications,
        )
    }

    /** Tarefas + rotinas de hoje, para o resumo e os widgets. */
    val todayRepository: TodayRepository by lazy { TodayRepository(repository, routineRepository) }

    val historyExporter: HistoryExporter by lazy { HistoryExporter(database, contentResolver) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsDataStore) }

    val dailyScheduler: DailyScheduler by lazy { DailyScheduler(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        createNotificationChannels()
        keepDailyAlarmsInSync()
        keepWidgetsInSync()
        // "Forçar parada" (inclusive por otimizadores de bateria de fabricantes) apaga os alarmes do app,
        // e antes do Android 15 nada avisa quando ele volta a abrir. Reagendar é idempotente.
        appScope.launch {
            repository.rescheduleReminders()
            routineRepository.scheduleNextAlarm()
        }
    }

    /**
     * Agenda os alarmes diários em todo início de processo (inclusive após reiniciar o aparelho)
     * e de novo a cada mudança nas configurações (ligar/desligar, novo horário).
     */
    private fun keepDailyAlarmsInSync() {
        appScope.launch {
            settingsRepository.settings.collect { dailyScheduler.scheduleAll(it) }
        }
    }

    /**
     * Redesenha os widgets quando o que eles mostram muda (por app, widget, alarme ou pela passagem
     * do tempo, quando o "a seguir" vence). A primeira
     * emissão é ignorada: ao iniciar o processo os widgets já estão em dia, e redesenhá-los ali só
     * disputaria CPU com a abertura do app.
     */
    @OptIn(FlowPreview::class)
    private fun keepWidgetsInSync() {
        appScope.launch {
            todayRepository.observe()
                .distinctUntilChanged()
                .drop(1)
                .debounce(WIDGET_UPDATE_DEBOUNCE_MS)
                .collect { KalendrysWidgets.updateAll(this@KalendrysApp) }
        }
    }

    /** Só no debug: acusa no logcat (tag StrictMode) disco/rede na thread principal e recursos vazados. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }

    private fun createNotificationChannels() {
        val reminders = NotificationChannel(
            REMINDER_CHANNEL_ID,
            getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        val summary = NotificationChannel(
            SUMMARY_CHANNEL_ID,
            getString(R.string.summary_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.summary_channel_description) }
        // Padrão (sem saltar na tela): uma rotina cheia de blocos avisa várias vezes por dia.
        val routines = NotificationChannel(
            ROUTINE_CHANNEL_ID,
            getString(R.string.routine_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.routine_channel_description) }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(reminders, summary, routines))
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "reminders"
        const val SUMMARY_CHANNEL_ID = "daily_summary"
        const val ROUTINE_CHANNEL_ID = "routines"
        private const val WIDGET_UPDATE_DEBOUNCE_MS = 500L
    }
}
