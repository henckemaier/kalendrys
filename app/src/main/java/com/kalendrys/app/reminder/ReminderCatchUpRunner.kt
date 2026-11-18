package com.kalendrys.app.reminder

import com.kalendrys.app.data.local.entity.Task
import com.kalendrys.app.data.repository.TaskRepository
import com.kalendrys.app.data.settings.SettingsRepository
import com.kalendrys.app.domain.ReminderRepeat
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Caminho ÚNICO de entrega de lembretes (ADR-0003). O alarme só acorda o app; quem entrega é esta
 * rotina, chamada pelo alarme ([ReminderReceiver]), pelo início do processo
 * ([com.kalendrys.app.KalendrysApp]), pelo boot/mudança de hora ([BootReceiver]), pela volta ao
 * primeiro plano ([com.kalendrys.app.MainActivity]) e antes de gravar tarefa (gancho
 * `beforeReminderChange` do [TaskRepository]). Quem chega primeiro entrega; quem chega depois
 * encontra a janela vazia.
 *
 * Ordem fixa, com um único `now` e uma execução por vez ([mutex]):
 * 1. lê `lastSeen`; sem marca (primeira execução), grava `now`, agenda e sai;
 * 2. monta o plano (puro, [com.kalendrys.app.domain.ReminderCatchUp]);
 * 3. mostra as atrasadas e o agregado das perdidas;
 * 4. agenda a próxima ocorrência de cada tarefa estritamente depois de `now`;
 * 5. só então grava `lastSeen = now`.
 *
 * Só esta classe grava `lastSeen`. Se o processo morrer entre 3 e 5, a próxima execução reexibe as
 * mesmas ocorrências com o mesmo id de notificação: repetir é preferível a perder.
 */
class ReminderCatchUpRunner(
    private val repository: TaskRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** O que o catch-up mostra; no app, notificações e widgets ([AndroidCatchUpNotifier]). */
    interface Notifier {
        /**
         * Lembrete normal da tarefa (Concluir, Adiar, Abrir lista), com o id da tarefa. [firedAt] é o
         * instante da OCORRÊNCIA entregue: a notificação leva o dia dela nas ações (NB-E5-A).
         */
        suspend fun showReminder(task: Task, firedAt: Long)

        /** Aviso agregado "N lembretes perdidos" (id fixo: uma nova contagem substitui a anterior). */
        fun showMissed(count: Int)

        /** Alarmes ou lembretes mudaram: o "próximo lembrete" dos widgets precisa ser redesenhado. */
        suspend fun remindersChanged()
    }

    /**
     * Uma execução do catch-up por vez. ORDEM DAS TRAVAS (NB-E1-B da v1.8.0): esta por FORA, a trava de
     * alarmes do [TaskRepository] por DENTRO (o [TaskRepository.rescheduleReminders] a pega aqui dentro),
     * nunca o contrário: o repositório chama o catch-up (fechar a janela) sempre fora da trava dele.
     */
    private val mutex = Mutex()

    /**
     * [nowOverride] só para quem já tem o "agora" (testes). Sem ele, o relógio é lido DENTRO do Mutex:
     * lido antes, duas chamadas quase simultâneas podiam entrar em ordem trocada e a segunda cair em
     * "relógio voltou", gravando a marca para trás e reentregando uma ocorrência.
     */
    suspend fun run(nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock()
        val lastSeen = settings.lastSeenAt()
        if (lastSeen == null) {
            // Primeira execução de sempre: estabelece a linha de base, sem "recuperar" o passado todo.
            settings.setLastSeen(now)
            repository.rescheduleReminders(now)
            return@withLock
        }
        val (plan, lateTasks) = repository.reminderCatchUpPlan(lastSeen, now, zone())
        // Da entrega até gravar a marca, sem cancelamento (tela que fecha, ViewModel que morre): cortado
        // no meio, o lembrete já mostrado seria reexibido na próxima execução.
        withContext(NonCancellable) {
            for (occurrence in plan.late) {
                val task = lateTasks[occurrence.taskId] ?: continue
                notifier.showReminder(task, occurrence.firedAt)
                // Higiene: o alarme da tarefa única que sobrar pendente tocaria numa janela vazia.
                if (task.repeat == ReminderRepeat.NONE) repository.cancelReminderAlarm(task.id)
            }
            if (plan.missedCount > 0) notifier.showMissed(plan.missedCount)
            // Reagendar só DEPOIS de entregar: substituir um alarme pendente não perde a ocorrência.
            repository.rescheduleReminders(now)
            settings.setLastSeen(now)
            if (!plan.isEmpty) notifier.remindersChanged()
        }
    }

    /**
     * Depois de restaurar um backup (ADR-0003, 4.2): a marca passa a ser `now` e os alarmes são
     * refeitos, sem entregar nada do passado do backup.
     */
    suspend fun establishBaseline(nowOverride: Long? = null) = mutex.withLock {
        val now = nowOverride ?: clock()
        settings.setLastSeen(now)
        repository.rescheduleReminders(now)
    }
}
