package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon

/**
 * Bloco de horário que se repete nos dias da semana escolhidos (ex.: Treino, 17:20–17:35, seg a sex).
 *
 * Ícone e cor ficam como texto, não como enum do Room: um valor removido numa versão futura
 * cai no padrão ([RoutineIcon.fromKey]) em vez de derrubar a leitura do banco.
 *
 * Excluir uma rotina só preenche [archivedAt]: os dias anteriores continuam mostrando o bloco e
 * a conclusão (histórico); editar, por outro lado, muda também como os dias passados aparecem.
 */
@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val icon: String = RoutineIcon.DEFAULT.name,
    val color: String = RoutineColor.DEFAULT.name,
    /** Minutos desde a meia-noite (0..1439), no relógio local. */
    val startMinute: Int,
    /** 0 = sem duração (um marco no horário). */
    val durationMinutes: Int = 0,
    /** Máscara de [RoutineDays]: bit 0 = segunda … bit 6 = domingo. */
    val daysOfWeek: Int = RoutineDays.ALL,
    /** Avisar com uma notificação na hora em que o bloco começa. */
    val remind: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val archivedAt: Long? = null,
    /**
     * Categoria à qual a rotina pertence ([RoutineCategory.id]), ou `null` se sem categoria. Só a
     * coluna existe nesta versão; a lógica de categorias é da task seguinte. Sem foreign key.
     */
    val categoryId: Long? = null,
    /** Momento em que a rotina foi pausada (epoch millis), ou `null` se ativa. Lógica virá depois. */
    val pausedAt: Long? = null,
    /** Intervalo em semanas para a recorrência ("a cada N semanas"); 1 = toda semana. Lógica virá depois. */
    @ColumnInfo(defaultValue = "1") val weekInterval: Int = 1,
    /** Primeiro dia de vigência da rotina (epoch day), ou `null` para sem início definido. */
    val startEpochDay: Long? = null,
    /** Último dia de vigência da rotina (epoch day), ou `null` para sem fim definido. */
    val endEpochDay: Long? = null,
) {
    val endMinute: Int get() = startMinute + durationMinutes
}
