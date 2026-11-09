package com.kalendrys.app.ui.routineeditor

import com.kalendrys.app.data.local.entity.Routine
import com.kalendrys.app.domain.RoutineColor
import com.kalendrys.app.domain.RoutineDays
import com.kalendrys.app.domain.RoutineIcon

/** Cópia editável de uma rotina no editor; só vai para o banco ao salvar. */
data class RoutineDraft(
    val title: String = "",
    val notes: String = "",
    val icon: RoutineIcon = RoutineIcon.DEFAULT,
    val color: RoutineColor = RoutineColor.DEFAULT,
    val startMinute: Int,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val daysOfWeek: Int = RoutineDays.ALL,
    val remind: Boolean = true,
    /** Categoria à qual a rotina pertence ([RoutineCategory.id]), ou null se sem categoria. */
    val categoryId: Long? = null,
) {
    val hasDays: Boolean get() = daysOfWeek and RoutineDays.ALL != 0

    val isValid: Boolean get() = title.isNotBlank() && hasDays

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
            categoryId = categoryId,
        )

    fun toNewRoutine(now: Long = System.currentTimeMillis()): Routine =
        applyTo(Routine(title = "", startMinute = startMinute, createdAt = now))

    companion object {
        const val DEFAULT_DURATION_MINUTES = 30
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
            categoryId = routine.categoryId,
        )
    }
}
