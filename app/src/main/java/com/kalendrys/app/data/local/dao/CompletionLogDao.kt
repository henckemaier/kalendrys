package com.kalendrys.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.kalendrys.app.data.local.entity.CompletionLog
import kotlinx.coroutines.flow.Flow

/** Linha de [CompletionLogDao.observeLastCompletions]. */
data class LastCompletion(val taskId: Long, val completedAt: Long)

@Dao
interface CompletionLogDao {

    @Insert
    suspend fun insert(log: CompletionLog): Long

    /** Reinsere em lote preservando os ids (restauro de backup); use dentro de uma transação. */
    @Insert
    suspend fun insertAll(logs: List<CompletionLog>)

    @Query("DELETE FROM completion_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Apaga o histórico inteiro (restauro de backup limpa antes de reinserir). */
    @Query("DELETE FROM completion_log")
    suspend fun clear()

    /** Conclusões em [start, end) — usa o índice de completedAt; nunca carrega o histórico todo. */
    @Query("SELECT * FROM completion_log WHERE completedAt >= :start AND completedAt < :end ORDER BY completedAt ASC")
    fun observeBetween(start: Long, end: Long): Flow<List<CompletionLog>>

    /** Leitura única de [observeBetween], sem montar um Flow (catch-up de lembretes). */
    @Query("SELECT * FROM completion_log WHERE completedAt >= :start AND completedAt < :end ORDER BY completedAt ASC")
    suspend fun between(start: Long, end: Long): List<CompletionLog>

    @Query("SELECT * FROM completion_log WHERE taskId = :taskId ORDER BY completedAt DESC")
    fun observeForTask(taskId: Long): Flow<List<CompletionLog>>

    /** Última conclusão de cada tarefa: decide se uma tarefa sem repetição está encerrada. */
    @Query("SELECT taskId, MAX(completedAt) AS completedAt FROM completion_log GROUP BY taskId")
    fun observeLastCompletions(): Flow<List<LastCompletion>>

    @Query("SELECT taskId, MAX(completedAt) AS completedAt FROM completion_log GROUP BY taskId")
    suspend fun lastCompletions(): List<LastCompletion>

    @Query("SELECT MAX(completedAt) FROM completion_log WHERE taskId = :taskId")
    suspend fun lastCompletion(taskId: Long): Long?

    @Query("SELECT * FROM completion_log WHERE id = :id")
    suspend fun byId(id: Long): CompletionLog?

    /** Registro mais recente da tarefa (concluir de novo uma tarefa única encerrada o devolve, NB-3). */
    @Query("SELECT * FROM completion_log WHERE taskId = :taskId ORDER BY completedAt DESC, id DESC LIMIT 1")
    suspend fun latestForTask(taskId: Long): CompletionLog?

    @Query("SELECT EXISTS(SELECT 1 FROM completion_log WHERE taskId = :taskId AND completedAt >= :start AND completedAt < :end)")
    suspend fun isCompletedBetween(taskId: Long, start: Long, end: Long): Boolean

    /** O histórico inteiro, lido uma vez só para a exportação em CSV. */
    @Query("SELECT * FROM completion_log ORDER BY completedAt ASC")
    suspend fun all(): List<CompletionLog>

    @Query("SELECT COUNT(*) FROM completion_log")
    suspend fun count(): Int

    /**
     * Grava SÓ a data e o lembrete da tarefa, dentro das transações abaixo (concluir adiantado e
     * desfazer). Atualização PARCIAL (v1.9.0, 15.2): concluir e desfazer nunca regravam título, texto
     * ou lista a partir de uma leitura antiga. Fora delas a tarefa se grava pelo TaskDao; não chame daqui.
     */
    @Query("UPDATE tasks SET dueDate = :dueDate, reminderAt = :reminderAt WHERE id = :taskId")
    suspend fun updateTaskDateInCompletion(taskId: Long, dueDate: Long?, reminderAt: Long?)

    /**
     * Conclusão ADIANTADA de tarefa única (banco 11): grava a data reescrita da tarefa (sem lembrete)
     * e o registro com o que ela sobrescreveu numa transação só. Sem ela, uma falha entre os dois
     * deixaria a data trocada sem registro para o Desfazer devolver.
     */
    @Transaction
    suspend fun rewriteAndInsert(taskId: Long, rewrittenDueDate: Long, log: CompletionLog): Long {
        updateTaskDateInCompletion(taskId, rewrittenDueDate, reminderAt = null)
        return insert(log)
    }

    /**
     * Desfazer: apaga o registro e, se for o caso ([restore] não nulo), devolve a data e o lembrete
     * da tarefa numa transação só. Sem ela, morrer entre as duas gravações deixaria a tarefa com a
     * data reescrita e sem o registro para restaurar.
     */
    @Transaction
    suspend fun deleteAndRestore(logId: Long, restore: DateRestore?) {
        deleteById(logId)
        restore?.let { updateTaskDateInCompletion(it.taskId, it.dueDate, it.reminderAt) }
    }
}

/** Data e lembrete que o Desfazer devolve à tarefa ([CompletionLogDao.deleteAndRestore]). */
data class DateRestore(val taskId: Long, val dueDate: Long?, val reminderAt: Long?)
