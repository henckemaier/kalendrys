package com.kalendrys.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import com.kalendrys.app.data.local.AppDatabase
import com.kalendrys.app.data.repository.BackupManager
import com.kalendrys.app.data.repository.ExerciseRepository
import com.kalendrys.app.data.repository.HistoryExporter
import com.kalendrys.app.data.repository.RoutineCatchUpMark
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.data.repository.RoutineRepository
import com.kalendrys.app.data.repository.ShoppingRepository
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.repository.TodayRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.data.settings.settingsDataStore
import com.kalendrys.app.reminder.AndroidCatchUpNotifier
import com.kalendrys.app.reminder.ReminderCatchUpRunner
import com.kalendrys.app.reminder.ReminderScheduler
import com.kalendrys.app.reminder.RoutineAlarms
import com.kalendrys.app.reminder.RoutineNotifications
import com.kalendrys.app.summary.DailySummaryCatchUp
import com.kalendrys.app.summary.DailyScheduler
import com.kalendrys.app.summary.DailySummaryNotifier
import com.kalendrys.app.summary.summaryChanges
import com.kalendrys.app.update.UpdateChecker
import com.kalendrys.app.ui.theme.AppNightMode
import com.kalendrys.app.ui.theme.Appearance
import com.kalendrys.app.ui.theme.appearance
import com.kalendrys.app.update.UpdateInstaller
import com.kalendrys.app.widget.KalendrysWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch

/**
 * Injeção manual de dependências: tudo criado sob demanda e compartilhado
 * via `context.applicationContext as KalendrysApp`.
 */
class KalendrysApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val repository: TaskRepository by lazy {
        TaskRepository(
            database.taskDao(),
            database.completionLogDao(),
            ReminderScheduler(this),
            database.shoppingItemDao(),
            // Fecha a janela do catch-up antes de gravar (ADR-0003): horário já passado não vira atrasado.
            beforeReminderChange = { reminderCatchUp.run() },
        )
    }

    /** Caminho único de entrega de lembretes de tarefa (ADR-0003). */
    val reminderCatchUp: ReminderCatchUpRunner by lazy {
        ReminderCatchUpRunner(repository, settingsRepository, AndroidCatchUpNotifier(this, shoppingRepository))
    }

    val routineNotifications: RoutineNotifications by lazy { RoutineNotifications(this) }

    val routineRepository: RoutineRepository by lazy {
        RoutineRepository(
            database.routineDao(),
            database.routineCompletionDao(),
            database.routineOverrideDao(),
            RoutineAlarms(this),
            routineNotifications,
            database.shoppingItemDao(),
            // Entrega do catch-up do aviso de bloco (ADR-0003, 4.3.1): o repositório decide, aqui mostra.
            deliverDueBlocks = { due, date ->
                due.forEach { routine ->
                    routineNotifications.show(
                        routine,
                        date,
                        routineRepository.overridesFor(routine.id),
                        shoppingRepository.progressOf(routine.shoppingListId),
                    )
                }
            },
            mark = object : RoutineCatchUpMark {
                override suspend fun get(): Long? = settingsRepository.routineLastSeenAt()

                override suspend fun set(millis: Long) = settingsRepository.setRoutineLastSeen(millis)
            },
        )
    }

    val routineCategoryRepository: RoutineCategoryRepository by lazy {
        RoutineCategoryRepository(database)
    }

    /** Listas de compras independentes (ADR-0001): aba Listas, rotinas e tarefas. */
    val shoppingRepository: ShoppingRepository by lazy {
        ShoppingRepository(database)
    }

    /** Lista de exercícios das rotinas do tipo EXERCICIO (módulo T33/T34). */
    val exerciseRepository: ExerciseRepository by lazy {
        ExerciseRepository(database.exerciseItemDao())
    }

    /** Tarefas + rotinas de hoje, para o resumo e os widgets. */
    val todayRepository: TodayRepository by lazy { TodayRepository(repository, routineRepository, shoppingRepository) }

    val historyExporter: HistoryExporter by lazy { HistoryExporter(database, contentResolver) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsDataStore) }

    /**
     * Painel de nova tarefa (v1.8.0, E6): dono único do rascunho e das operações dele (salvar no escopo
     * do app, listas do seletor). Só em memória. Aberto ou fechado é estado de tela (NewTaskHost).
     */
    val newTaskPanel: NewTaskPanel by lazy { NewTaskPanel(repository, shoppingRepository, appScope) }

    /** Desfazer das exclusões que fecham a tela (tarefa e rotina), na origem; só em memória (E0-D, P8). */
    val undoOffers: UndoOffers by lazy { UndoOffers(repository, routineRepository, appScope) }

    /**
     * Backup/restauro completo. Depois de restaurar (substitui tudo), reagenda os alarmes e
     * atualiza os widgets pelos caminhos idempotentes de sempre — senão o aparelho novo fica
     * com os dados mas sem nenhum alarme. Cada catch-up grava a sua marca = agora e só agenda: nada do
     * passado do backup é entregue (ADR-0003, 4.3.3).
     */
    val backupManager: BackupManager by lazy {
        BackupManager(
            database = database,
            contentResolver = contentResolver,
            settings = settingsRepository,
            afterRestore = {
                // Marca = agora e só agenda: o passado do backup não vira atrasado nem perdido (ADR-0003, 4.2).
                reminderCatchUp.establishBaseline()
                routineRepository.establishBaseline()
                summaryCatchUp.establishBaseline()
                KalendrysWidgets.updateAll(this)
            },
        )
    }

    val dailyScheduler: DailyScheduler by lazy { DailyScheduler(this) }

    /** Catch-up do resumo diário (ADR-0003, 4.3.2): decide pela janela se o resumo sai, depois agenda. */
    val summaryCatchUp: DailySummaryCatchUp by lazy {
        DailySummaryCatchUp(
            settings = settingsRepository,
            schedule = { settings, now -> dailyScheduler.scheduleAll(settings, now) },
            show = { DailySummaryNotifier.show(this, todayRepository.current()) },
        )
    }

    /** Atualizador in-app (botão manual em Configurações): checagem e instalação via GitHub Releases. */
    val updateChecker: UpdateChecker by lazy { UpdateChecker() }

    val updateInstaller: UpdateInstaller by lazy { UpdateInstaller(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Escopo das gravações do salvar na hora (v1.9.0, ADR-0002 5.2 e 15.3): o mesmo do painel de nova
     * tarefa e das ofertas de Desfazer. A gravação final de um editor, ao sair da tela ou ir para
     * segundo plano, roda aqui e não no viewModelScope, que é cancelado ao fechar a tela. Só para
     * gravar; nada de estado de tela aqui.
     */
    val editScope: CoroutineScope get() = appScope

    private val _appearance = MutableStateFlow<Appearance?>(null)

    /**
     * Tema e cor dinâmica (ADR-0002, 12.3), lidos do DataStore a partir do início do processo.
     * `null` até a primeira leitura: a MainActivity segura o primeiro quadro até o valor chegar, para
     * o app não abrir num tema e trocar para outro (sem runBlocking).
     */
    val appearance: StateFlow<Appearance?> = _appearance.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // Primeiro: a leitura do DataStore começa já, em paralelo com o resto da abertura.
        keepAppearanceInSync()
        if (BuildConfig.DEBUG) enableStrictMode()
        createNotificationChannels()
        keepDailyAlarmsInSync()
        keepWidgetsInSync()
        // "Forçar parada" (inclusive por otimizadores de bateria de fabricantes) apaga os alarmes do app,
        // e antes do Android 15 nada avisa quando ele volta a abrir. Os catch-ups entregam o que venceu e
        // SÓ ENTÃO reagendam (ADR-0003): reagendar direto substituía o alarme atrasado de hoje. O do
        // resumo roda na primeira emissão do [keepDailyAlarmsInSync].
        appScope.launch {
            reminderCatchUp.run()
            routineRepository.catchUp()
        }
    }

    /**
     * Publica a aparência e aplica o espelho no modo noturno do app (Android 12+, [AppNightMode]),
     * inclusive na primeira emissão: cobre quem atualizou e quem restaurou um backup. Único ponto que
     * chama o sistema (parecer do Esquadro); Configurações e o backup só gravam no DataStore. Fora do
     * caminho do primeiro quadro: a chamada roda aqui, no coletor, não no onCreate.
     *
     * Quando a Aparência MUDA (não na primeira emissão), redesenha os widgets (ADR-0002, 12.5): eles
     * seguem a Aparência e são desenhados pelo launcher, que não recebe o espelho do modo noturno.
     */
    private fun keepAppearanceInSync() {
        val nightMode = AppNightMode(this)
        appScope.launch {
            settingsRepository.settings.map { it.appearance() }.collectAppearance(
                onEach = { appearance ->
                    _appearance.value = appearance
                    nightMode.apply(appearance.themeMode)
                },
                onChanged = { KalendrysWidgets.updateAll(this@KalendrysApp) },
            )
        }
    }

    /**
     * Resumo diário em todo início de processo (inclusive após reiniciar o aparelho) e a cada mudança
     * nas configurações (ADR-0003, 4.3.2). A primeira emissão é o início de processo: catch-up, que
     * entrega o resumo atrasado antes de reagendar. As seguintes são mudança de configuração (ligar,
     * desligar, novo horário): marca = agora e só agenda, sem entregar um resumo "atrasado".
     */
    private fun keepDailyAlarmsInSync() {
        appScope.launch {
            settingsRepository.settings.summaryChanges().withIndex().collect { (index, settings) ->
                if (index == 0) summaryCatchUp.run() else summaryCatchUp.establishBaseline(settings)
            }
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
