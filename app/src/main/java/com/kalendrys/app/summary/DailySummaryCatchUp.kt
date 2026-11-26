package com.kalendrys.app.summary

import com.kalendrys.app.data.repository.AppClock
import com.kalendrys.app.data.settings.AppSettings
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.SummaryCatchUp
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
    /** O relógio do app (v1.10.0, 16.3). */
    private val clock: AppClock,
) {

    private val mutex = Mutex()

    suspend fun run(nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock.nowMillis()
        val current = settings.settings.first()
        val lastSeen = settings.summaryLastSeenAt()
        withContext(NonCancellable) {
            // Primeira execução (inclusive depois de atualizar da v1.6.0): só estabelece a marca.
            if (lastSeen != null && SummaryCatchUp.due(current.summaryEnabled, current.summaryTime, lastSeen, now, clock.zone()) != null) {
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
        val now = nowOverride ?: clock.nowMillis()
        val current = newSettings ?: settings.settings.first()
        withContext(NonCancellable) {
            schedule(current, localDateTime(now))
            settings.setSummaryLastSeen(now)
        }
    }

    private fun localDateTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), clock.zone())
}

/**
 * Só as mudanças que importam ao resumo (ligado e horário). O `keepDailyAlarmsInSync` do KalendrysApp faz
 * linha de base a cada emissão depois da primeira; quando outras preferências entrarem em [AppSettings]
 * (tema, cor), mudá-las não pode zerar a janela de um resumo atrasado.
 */
fun Flow<AppSettings>.summaryChanges(): Flow<AppSettings> =
    distinctUntilChangedBy { it.summaryEnabled to it.summaryTime }
