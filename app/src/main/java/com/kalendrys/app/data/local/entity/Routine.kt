package com.kalendrys.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.domain.RoutineKind

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
    val createdAt: Long,
    val archivedAt: Long? = null,
    /**
     * SEM USO nas telas desde a v1.10.0 (ADR-0002 16.10.1): a categoria só decidiu o [kind] na
     * migração 11→12 e no restauro de backup antigo. Sem foreign key.
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
    /**
     * SEM USO desde a v1.10.0 (ADR-0002 16.10.3): rotina não tem lista. A migração 11→12 e o restauro
     * apagam o vínculo; a coluna fica porque tirá-la exige recriar a tabela de rotinas.
     */
    @ColumnInfo(defaultValue = "NULL") val shoppingListId: Long? = null,
    /**
     * Tipo da rotina ([RoutineKind]: Nenhum ou Exercício), pelo nome (v1.10.0, banco 12). Os
     * exercícios ([ExerciseItem]) continuam guardados com qualquer tipo e só aparecem no Exercício.
     */
    @ColumnInfo(defaultValue = "NONE") val kind: String = RoutineKind.DEFAULT.name,
) {
    val endMinute: Int get() = startMinute + durationMinutes
}
