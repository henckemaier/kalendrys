package com.kalendrys.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Registro de eventos: cada conclusão vira uma linha imutável. A única remoção
 * permitida é o "Desfazer" logo após concluir, que cancela um toque acidental.
 * Sem foreign key de propósito — o histórico sobrevive à exclusão da tarefa,
 * então os relatórios nunca perdem dados.
 *
 * O índice de completedAt atende as consultas por período (hoje, mês do calendário).
 */
@Entity(
    tableName = "completion_log",
    indices = [Index("taskId"), Index("completedAt")],
)
data class CompletionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val completedAt: Long,
)
