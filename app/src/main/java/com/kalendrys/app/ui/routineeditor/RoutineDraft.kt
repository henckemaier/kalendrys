package com.kalendrys.app.ui.routineeditor

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon
import com.kalendrys.app.domain.RoutineKind

/**
 * Campos do editor de rotina (v1.9.0, ADR-0002 15.2 e 15.5). Na CRIAÇÃO é o rascunho, que só vai para
 * o banco no "Criar rotina" ([toNewRoutine]). Na EDIÇÃO é só o retrato do que a tela mostra (a linha
 * do banco com o texto em digitação) e o retrato da abertura para o "Reverter alterações": nunca é
 * gravado inteiro; cada mudança vai pelo [com.kalendrys.app.data.repository.RoutineRepository.edit].
 */
data class RoutineDraft(
    val title: String = "",
    val notes: String = "",
    val icon: RoutineIcon = RoutineIcon.DEFAULT,
    val color: RoutineColor = RoutineColor.DEFAULT,
    val startMinute: Int,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val daysOfWeek: Int = RoutineDays.ALL,
    val remind: Boolean = true,
    /**
     * Tipo (v1.10.0, ADR-0002 16.10.5). Só [RoutineKind.EXERCISE] mostra os exercícios; trocar para
     * [RoutineKind.NONE] os esconde e guarda. A categoria antiga da linha não é do editor.
     */
    val kind: RoutineKind = RoutineKind.DEFAULT,
    /** "A cada N semanas"; 1 = toda semana. */
    val weekInterval: Int = 1,
    /** Dia (epoch day) a partir do qual a rotina vale, ou null para sem início definido. */
    val startEpochDay: Long? = null,
    /** Último dia (epoch day) em que a rotina vale (rotina temporária), ou null para sem fim. */
    val endEpochDay: Long? = null,
) {
    val hasDays: Boolean get() = daysOfWeek and RoutineDays.ALL != 0

    /** Fim não pode vir antes do início quando ambos estão definidos. */
    val datesValid: Boolean get() = startEpochDay == null || endEpochDay == null || startEpochDay <= endEpochDay

    val isValid: Boolean get() = title.isNotBlank() && hasDays && datesValid

    /** Minuto do dia em que termina (0..1439). */
    val endMinuteOfDay: Int get() = (startMinute + durationMinutes) % MINUTES_PER_DAY

    val endsNextDay: Boolean get() = durationMinutes > 0 && startMinute + durationMinutes >= MINUTES_PER_DAY

    /**
     * "Termina às": a duração sai da diferença até [endMinuteOfDay]. Um fim antes do início passa
     * da meia-noite; o mesmo horário do início vira "sem duração".
     */
    fun withEnd(endMinuteOfDay: Int): RoutineDraft =
        copy(durationMinutes = (endMinuteOfDay - startMinute + MINUTES_PER_DAY) % MINUTES_PER_DAY)

    fun differsFrom(routine: Routine): Boolean = applyTo(routine) != routine

    fun applyTo(routine: Routine): Routine =
        routine.copy(
            title = title.trim(),
            notes = notes.trim(),
            icon = icon.name,
            color = color.name,
            startMinute = startMinute,
            durationMinutes = durationMinutes,
            daysOfWeek = daysOfWeek,
            remind = remind,
            kind = kind.name,
            weekInterval = weekInterval.coerceIn(1, MAX_WEEK_INTERVAL),
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
        )

    /** [now]: o instante de criação, do relógio do app (v1.10.0, 16.3). */
    fun toNewRoutine(now: Long): Routine =
        applyTo(Routine(title = "", startMinute = startMinute, createdAt = now))

    companion object {
        const val DEFAULT_DURATION_MINUTES = 30
        const val MAX_WEEK_INTERVAL = 12
        private const val MINUTES_PER_DAY = 24 * 60

        /** Atalhos de duração no editor (0 = sem duração). */
        val DURATION_PRESETS = listOf(0, 5, 10, 15, 30, 45, 60, 90, 120)

        fun from(routine: Routine) = RoutineDraft(
            title = routine.title,
            notes = routine.notes,
            icon = RoutineIcon.fromKey(routine.icon),
            color = RoutineColor.fromKey(routine.color),
            startMinute = routine.startMinute,
            durationMinutes = routine.durationMinutes,
            daysOfWeek = routine.daysOfWeek,
            remind = routine.remind,
            kind = RoutineKind.fromKey(routine.kind),
            weekInterval = routine.weekInterval.coerceIn(1, MAX_WEEK_INTERVAL),
            startEpochDay = routine.startEpochDay,
            endEpochDay = routine.endEpochDay,
        )
    }
}
