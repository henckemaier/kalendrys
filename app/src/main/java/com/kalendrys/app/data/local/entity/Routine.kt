package com.kalendrys.app.data.local.entity

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
) {
    val endMinute: Int get() = startMinute + durationMinutes
}
