package com.kalendrys.app.summary

import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.SummaryCatchUp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Catch-up do resumo diário (ADR-0003, 4.3.2). O alarme do resumo só acorda o app; quem decide
 * se o resumo sai é a janela `(summary_last_seen_at, agora]` ([SummaryCatchUp]). Chamado pelo
 * [DailyReceiver], pelo início de processo, pelo boot e pela volta ao primeiro plano.
 *
 * Ordem fixa sob Mutex próprio: entrega, agenda ([DailyScheduler.scheduleAll], estritamente depois
 * de agora), grava a marca. Só esta classe grava a marca do resumo. Como no runner das tarefas, o
 * relógio é lido dentro do Mutex e o trecho de entrega a marca não é cancelável.
 */
class DailySummaryCatchUp(
    private val settings: SettingsRepository,
    /** Agenda o resumo e a virada do dia; no app, [DailyScheduler.scheduleAll]. */
    private val schedule: (AppSettings, LocalDateTime) -> Unit,
    /** Mostra o resumo de hoje; no app, [DailySummaryNotifier.show] com o retrato do dia. */
    private val show: suspend () -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val mutex = Mutex()

    suspend fun run(nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock()
        val current = settings.settings.first()
        val lastSeen = settings.summaryLastSeenAt()
        withContext(NonCancellable) {
            // Primeira execução (inclusive depois de atualizar da v1.6.0): só estabelece a marca.
            if (lastSeen != null && SummaryCatchUp.due(current.summaryEnabled, current.summaryTime, lastSeen, now, zone()) != null) {
                show()
            }
            schedule(current, localDateTime(now))
            settings.setSummaryLastSeen(now)
        }
    }

    /**
     * Mudança nas Configurações (ligar, desligar, novo horário) e restauro de backup: marca = agora
     * e só agenda. Trocar para um horário já passado não entrega um resumo "atrasado".
     */
    suspend fun establishBaseline(newSettings: AppSettings? = null, nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock()
        val current = newSettings ?: settings.settings.first()
        withContext(NonCancellable) {
            schedule(current, localDateTime(now))
            settings.setSummaryLastSeen(now)
        }
    }

    private fun localDateTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone())
}
